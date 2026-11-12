//! One-time six-digit LAN redeem ticket.
//!
//! The digits are not a credential. They only name a `PairingPayload` this
//! process already built for an existing grant. The phone sends `{code, dk}`;
//! a match returns the same `luvia2:` string `encode` would print, and the
//! phone still runs `completePairing`. Tickets live in memory and die with
//! the process.

use std::collections::HashSet;
use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpListener, TcpStream};
use std::os::fd::FromRawFd;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::Deserialize;

use crate::error::{Error, Result};
use crate::grant::Grant;
use crate::hostinfo::is_tailnet_ip;
use crate::paircode::PairingPayload;

pub const DEFAULT_SHORT_TTL_SECS: u64 = 90;
pub const MIN_SHORT_TTL_SECS: u64 = 60;
pub const MAX_SHORT_TTL_SECS: u64 = 120;
pub const DEFAULT_SHORT_REDEEM_PORT: u16 = 8741;
const MAX_ATTEMPTS: u8 = 5;
const REDEEM_PATH: &str = "/pair/redeem";
const MAX_BODY: usize = 2048;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RedeemError {
    PeerNotAllowed,
    InvalidCode,
    DkMismatch,
    Expired,
    Exhausted,
}

impl RedeemError {
    pub fn code(self) -> &'static str {
        match self {
            Self::PeerNotAllowed => "peer_not_allowed",
            Self::InvalidCode => "invalid_code",
            Self::DkMismatch => "dk_mismatch",
            Self::Expired => "expired",
            Self::Exhausted => "exhausted",
        }
    }

    fn status(self) -> (u16, &'static str) {
        match self {
            Self::PeerNotAllowed | Self::DkMismatch => (403, "Forbidden"),
            Self::InvalidCode => (404, "Not Found"),
            Self::Expired | Self::Exhausted => (410, "Gone"),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ShortEnd {
    Redeemed,
    Expired,
    Exhausted,
}

impl ShortEnd {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Redeemed => "redeemed",
            Self::Expired => "expired",
            Self::Exhausted => "exhausted",
        }
    }

    pub fn message(self) -> &'static str {
        match self {
            Self::Redeemed => "short code redeemed",
            Self::Expired => "short code expired; the luvia2: code above still works",
            Self::Exhausted => {
                "short code invalidated after failed attempts; paste the luvia2: code or run pair --short again"
            }
        }
    }
}

#[derive(Clone, Debug)]
struct PeerPolicy {
    advertised: Vec<String>,
    allow_loopback: bool,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct BindAddr {
    ip: IpAddr,
}

struct ShortTicket {
    code: String,
    grant_id: String,
    dk: String,
    luvia1: String,
    exp_ms: u64,
    attempts_left: u8,
}

struct TicketTable {
    tickets: Vec<ShortTicket>,
    end: Option<ShortEnd>,
}

impl TicketTable {
    fn mint(
        &mut self,
        grant: &Grant,
        facts: &crate::hostinfo::HostFacts,
        ttl: Duration,
        now_ms: u64,
    ) -> Result<Minted> {
        let payload = PairingPayload::from_grant(grant, facts)?;
        let luvia1 = payload.encode()?;
        self.insert(grant, luvia1, ttl, now_ms)
    }

    fn insert(
        &mut self,
        grant: &Grant,
        luvia1: String,
        ttl: Duration,
        now_ms: u64,
    ) -> Result<Minted> {
        validate_ttl(ttl)?;
        let code = self.draw_unique_code()?;
        let exp_ms = now_ms.saturating_add(ttl.as_millis() as u64);
        self.tickets.retain(|ticket| ticket.grant_id != grant.id);
        self.tickets.push(ShortTicket {
            code: code.clone(),
            grant_id: grant.id.clone(),
            dk: grant.fingerprint.trim().to_string(),
            luvia1: luvia1.clone(),
            exp_ms,
            attempts_left: MAX_ATTEMPTS,
        });
        if self.end.is_some() && !self.tickets.is_empty() {
            self.end = None;
        }
        Ok(Minted { code, luvia1 })
    }

    fn draw_unique_code(&self) -> Result<String> {
        for _ in 0..8 {
            let code = random_code()?;
            if self.tickets.iter().any(|ticket| ticket.code == code) {
                continue;
            }
            return Ok(code);
        }
        Err(Error::new(
            "short_code",
            "could not draw a unique short code",
        ))
    }

    fn redeem(
        &mut self,
        code: &str,
        dk: &str,
        peer: IpAddr,
        now_ms: u64,
        policy: &PeerPolicy,
    ) -> std::result::Result<String, RedeemError> {
        if !is_redeem_peer(peer, &policy.advertised, policy.allow_loopback) {
            return Err(RedeemError::PeerNotAllowed);
        }
        let normalized = normalize_code(code);
        if let Some(code) = normalized.as_deref() {
            if let Some(index) = self.tickets.iter().position(|ticket| ticket.code == code) {
                if self.tickets[index].exp_ms <= now_ms {
                    self.tickets.remove(index);
                    self.note_empty(ShortEnd::Expired);
                    return Err(RedeemError::Expired);
                }
                if self.tickets[index].dk != dk.trim() {
                    return Err(self.consume_attempt(index, RedeemError::DkMismatch));
                }
                let luvia1 = self.tickets.remove(index).luvia1;
                self.note_empty(ShortEnd::Redeemed);
                return Ok(luvia1);
            }
        }
        let before = self.tickets.len();
        self.tickets.retain(|ticket| ticket.exp_ms > now_ms);
        if self.tickets.is_empty() {
            if before > 0 && self.end.is_none() {
                self.end = Some(ShortEnd::Expired);
                return Err(RedeemError::Expired);
            }
            return Err(match self.end {
                Some(ShortEnd::Expired) => RedeemError::Expired,
                _ => RedeemError::InvalidCode,
            });
        }
        Err(self.burn_all())
    }

    fn consume_attempt(&mut self, index: usize, kind: RedeemError) -> RedeemError {
        let left = self.tickets[index].attempts_left.saturating_sub(1);
        if left == 0 {
            self.tickets.remove(index);
            self.note_empty(ShortEnd::Exhausted);
            RedeemError::Exhausted
        } else {
            self.tickets[index].attempts_left = left;
            kind
        }
    }

    /// A miss costs one attempt on every live ticket. Five guesses burn the
    /// code; the remaining million combinations are never reachable.
    fn burn_all(&mut self) -> RedeemError {
        if self.tickets.is_empty() {
            return match self.end {
                Some(ShortEnd::Expired) => RedeemError::Expired,
                _ => RedeemError::InvalidCode,
            };
        }
        self.tickets.retain_mut(|ticket| {
            ticket.attempts_left = ticket.attempts_left.saturating_sub(1);
            ticket.attempts_left != 0
        });
        if self.tickets.is_empty() {
            self.end = Some(ShortEnd::Exhausted);
            return RedeemError::Exhausted;
        }
        RedeemError::InvalidCode
    }

    fn note_empty(&mut self, end: ShortEnd) {
        if self.tickets.is_empty() && self.end.is_none() {
            self.end = Some(end);
        }
    }
}

struct Minted {
    code: String,
    #[allow(dead_code)]
    luvia1: String,
}

pub fn validate_ttl_secs(secs: u64) -> Result<Duration> {
    let ttl = Duration::from_secs(secs);
    validate_ttl(ttl)?;
    Ok(ttl)
}

fn validate_ttl(ttl: Duration) -> Result<()> {
    let secs = ttl.as_secs();
    if ttl.subsec_nanos() != 0 || !(MIN_SHORT_TTL_SECS..=MAX_SHORT_TTL_SECS).contains(&secs) {
        return Err(Error::new(
            "invalid_ttl",
            format!("short code ttl must be {MIN_SHORT_TTL_SECS}-{MAX_SHORT_TTL_SECS} seconds"),
        ));
    }
    Ok(())
}

pub struct RunningShort {
    table: Arc<Mutex<TicketTable>>,
    server: RedeemServer,
    code: String,
    port: u16,
    ttl_secs: u64,
    targets: Vec<String>,
}

impl RunningShort {
    pub fn code(&self) -> &str {
        &self.code
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    pub fn ttl_secs(&self) -> u64 {
        self.ttl_secs
    }

    pub fn targets(&self) -> &[String] {
        &self.targets
    }

    pub fn banner(&self) -> String {
        let mut text = format!(
            "Short code · {}s · one time · LAN only\n\n{}\n\nOn the phone, enter these 6 digits and this computer's address.\nPaste or scan the luvia2: code if the short code expires.\n",
            self.ttl_secs,
            render_short_code(&self.code)
        );
        for target in &self.targets {
            text.push_str(&format!(
                "\n  {target}\n  POST http://{target}{REDEEM_PATH}\n"
            ));
        }
        text
    }

    pub fn wait(mut self) -> ShortEnd {
        let end = loop {
            let now = unix_ms();
            let table = self
                .table
                .lock()
                .unwrap_or_else(|poison| poison.into_inner());
            if let Some(end) = table.end {
                break end;
            }
            let live = table.tickets.iter().any(|ticket| ticket.exp_ms > now);
            drop(table);
            if !live {
                break ShortEnd::Expired;
            }
            thread::sleep(Duration::from_millis(100));
        };
        self.server.shutdown();
        end
    }
}

pub fn start_short(
    grant: &Grant,
    facts: &crate::hostinfo::HostFacts,
    ttl_secs: u64,
    port: u16,
) -> Result<RunningShort> {
    let ttl = validate_ttl_secs(ttl_secs)?;
    if port == 0 {
        return Err(Error::new(
            "invalid_port",
            "short code port must be non-zero",
        ));
    }
    let mut table = TicketTable {
        tickets: Vec::new(),
        end: None,
    };
    let minted = table.mint(grant, facts, ttl, unix_ms())?;
    debug_assert!(minted.luvia1.starts_with("luvia2:"));
    let shared = Arc::new(Mutex::new(table));
    // One wildcard listener. Binding each interface address misses same-host
    // connections to a LAN IP: macOS routes that destination out lo0, and an
    // IP-specific socket never accepts it. Public peers are still rejected.
    let server = serve_wildcard(shared.clone(), port, &facts.addrs, true)?;
    let bound_port = server.bound.first().map(|addr| addr.port()).unwrap_or(port);
    let targets = banner_targets(&local_bind_candidates(), &facts.addrs, bound_port);
    Ok(RunningShort {
        table: shared,
        server,
        code: minted.code,
        port: bound_port,
        ttl_secs,
        targets,
    })
}

/// Listen on `0.0.0.0:port` and, when it succeeds, `[::]:port` with v6-only.
/// Loopback is allowed so `curl http://127.0.0.1:<port>/pair/redeem` is a
/// same-host smoke test. Non-LAN peers are still refused.
fn serve_wildcard(
    table: Arc<Mutex<TicketTable>>,
    port: u16,
    advertised: &[String],
    allow_loopback: bool,
) -> Result<RedeemServer> {
    let v4 = SocketAddr::from((Ipv4Addr::UNSPECIFIED, port));
    let v4_listener = bind_listener(v4).map_err(|error| {
        Error::new(
            "redeem_bind",
            format!("could not bind the short-code redeem service on {v4}: {error}"),
        )
    })?;
    let v4_addr = v4_listener.local_addr()?;
    let mut listeners = vec![v4_listener];
    let mut bound = vec![v4_addr];
    let v6 = SocketAddr::from((Ipv6Addr::UNSPECIFIED, v4_addr.port()));
    if let Ok(listener) = bind_listener(v6) {
        if let Ok(actual) = listener.local_addr() {
            bound.push(actual);
            listeners.push(listener);
        }
    }
    Ok(spawn_server(
        table,
        listeners,
        bound,
        advertised,
        allow_loopback,
    ))
}

#[cfg(test)]
fn serve_explicit(
    table: Arc<Mutex<TicketTable>>,
    addr: SocketAddr,
    allow_loopback: bool,
    advertised: &[String],
) -> Result<RedeemServer> {
    let listener = bind_listener(addr)?;
    let actual = listener.local_addr()?;
    Ok(spawn_server(
        table,
        vec![listener],
        vec![actual],
        advertised,
        allow_loopback,
    ))
}

struct RedeemServer {
    shutdown: Arc<AtomicBool>,
    threads: Vec<JoinHandle<()>>,
    bound: Vec<SocketAddr>,
}

impl RedeemServer {
    fn shutdown(&mut self) {
        self.shutdown.store(true, Ordering::SeqCst);
        for handle in self.threads.drain(..) {
            let _ = handle.join();
        }
    }
}

impl Drop for RedeemServer {
    fn drop(&mut self) {
        self.shutdown();
    }
}

fn spawn_server(
    table: Arc<Mutex<TicketTable>>,
    listeners: Vec<TcpListener>,
    bound: Vec<SocketAddr>,
    advertised: &[String],
    allow_loopback: bool,
) -> RedeemServer {
    let shutdown = Arc::new(AtomicBool::new(false));
    let policy = PeerPolicy {
        advertised: advertised.to_vec(),
        allow_loopback,
    };
    let mut threads = Vec::new();
    for listener in listeners {
        let shutdown = shutdown.clone();
        let table = table.clone();
        let policy = policy.clone();
        let handle = thread::Builder::new()
            .name("luvia-redeem".into())
            .spawn(move || accept_loop(listener, shutdown, table, policy))
            .expect("spawn redeem listener");
        threads.push(handle);
    }
    RedeemServer {
        shutdown,
        threads,
        bound,
    }
}

fn accept_loop(
    listener: TcpListener,
    shutdown: Arc<AtomicBool>,
    table: Arc<Mutex<TicketTable>>,
    policy: PeerPolicy,
) {
    while !shutdown.load(Ordering::SeqCst) {
        match listener.accept() {
            Ok((stream, _)) => {
                let _ = stream.set_nonblocking(false);
                handle_client(stream, &table, &policy);
            }
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(30));
            }
            Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(_) => break,
        }
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct RedeemRequest {
    code: String,
    dk: String,
}

fn handle_client(mut stream: TcpStream, table: &Mutex<TicketTable>, policy: &PeerPolicy) {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(2)));
    let _ = stream.set_nodelay(true);
    let peer = match stream.peer_addr() {
        Ok(addr) => addr.ip(),
        Err(_) => return,
    };
    let request = match read_request(&mut stream) {
        Ok(request) => request,
        Err(ReadFail::TooLarge) => {
            write_json(
                &mut stream,
                413,
                "Payload Too Large",
                &error_body("bad_request"),
            );
            return;
        }
        Err(ReadFail::Bad) => {
            write_json(&mut stream, 400, "Bad Request", &error_body("bad_request"));
            return;
        }
    };
    if !is_redeem_peer(peer, &policy.advertised, policy.allow_loopback) {
        write_json(
            &mut stream,
            403,
            "Forbidden",
            &error_body(RedeemError::PeerNotAllowed.code()),
        );
        return;
    }
    let path = request.path.split('?').next().unwrap_or("");
    if path != REDEEM_PATH {
        write_json(&mut stream, 404, "Not Found", &error_body("invalid_code"));
        return;
    }
    if !request.method.eq_ignore_ascii_case("POST") {
        write_json(
            &mut stream,
            405,
            "Method Not Allowed",
            &error_body("bad_request"),
        );
        return;
    }
    let result = {
        let mut guard = table.lock().unwrap_or_else(|poison| poison.into_inner());
        match serde_json::from_slice::<RedeemRequest>(&request.body) {
            Ok(body) => guard.redeem(&body.code, &body.dk, peer, unix_ms(), policy),
            Err(_) => guard.redeem("------", "", peer, unix_ms(), policy),
        }
    };
    match result {
        Ok(luvia1) => write_json(&mut stream, 200, "OK", &ok_body(&luvia1)),
        Err(error) => {
            let (status, reason) = error.status();
            write_json(&mut stream, status, reason, &error_body(error.code()));
        }
    }
}

struct HttpRequest {
    method: String,
    path: String,
    body: Vec<u8>,
}

enum ReadFail {
    Bad,
    TooLarge,
}

fn read_request(stream: &mut TcpStream) -> std::result::Result<HttpRequest, ReadFail> {
    let mut buf = Vec::with_capacity(512);
    let mut tmp = [0u8; 512];
    let header_end = loop {
        if buf.len() > 8192 {
            return Err(ReadFail::TooLarge);
        }
        let read = stream.read(&mut tmp).map_err(|_| ReadFail::Bad)?;
        if read == 0 {
            return Err(ReadFail::Bad);
        }
        buf.extend_from_slice(&tmp[..read]);
        if let Some(pos) = find_subsequence(&buf, b"\r\n\r\n") {
            break pos;
        }
    };
    let header = std::str::from_utf8(&buf[..header_end]).map_err(|_| ReadFail::Bad)?;
    let mut lines = header.split("\r\n");
    let request_line = lines.next().ok_or(ReadFail::Bad)?;
    let mut parts = request_line.split(' ');
    let method = parts.next().ok_or(ReadFail::Bad)?.to_string();
    let path = parts.next().ok_or(ReadFail::Bad)?.to_string();
    if parts.next().is_none() {
        return Err(ReadFail::Bad);
    }
    let mut content_length = None;
    for line in lines {
        let Some((name, value)) = line.split_once(':') else {
            continue;
        };
        if name.eq_ignore_ascii_case("content-length") {
            content_length = Some(value.trim().parse::<usize>().map_err(|_| ReadFail::Bad)?);
        }
    }
    let Some(len) = content_length else {
        return Err(ReadFail::Bad);
    };
    if len > MAX_BODY {
        return Err(ReadFail::TooLarge);
    }
    let mut body = buf[header_end + 4..].to_vec();
    while body.len() < len {
        let read = stream.read(&mut tmp).map_err(|_| ReadFail::Bad)?;
        if read == 0 {
            return Err(ReadFail::Bad);
        }
        body.extend_from_slice(&tmp[..read]);
        if body.len() > MAX_BODY {
            return Err(ReadFail::TooLarge);
        }
    }
    body.truncate(len);
    Ok(HttpRequest { method, path, body })
}

fn find_subsequence(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}

fn write_json(stream: &mut TcpStream, status: u16, reason: &str, body: &str) {
    let message = format!(
        "HTTP/1.1 {status} {reason}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    let _ = stream.write_all(message.as_bytes());
}

fn error_body(code: &str) -> String {
    serde_json::json!({"error": code}).to_string()
}

fn ok_body(pairing_code: &str) -> String {
    serde_json::json!({"luvia2": pairing_code}).to_string()
}

fn format_target(addr: SocketAddr) -> String {
    match addr {
        SocketAddr::V4(v4) => format!("{}:{}", v4.ip(), v4.port()),
        SocketAddr::V6(v6) => format!("[{}]:{}", v6.ip(), v6.port()),
    }
}

pub fn render_short_code(code: &str) -> String {
    if code.len() != 6 || !code.bytes().all(|byte| byte.is_ascii_digit()) {
        return code.to_string();
    }
    let mut top = String::from("  ");
    let mut mid = String::from("  ");
    let mut bot = String::from("  ");
    for (index, ch) in code.chars().enumerate() {
        if index == 3 {
            top.push_str("  ");
            mid.push_str("  ");
            bot.push_str("  ");
        }
        top.push_str("╭───╮ ");
        mid.push_str(&format!("│ {ch} │ "));
        bot.push_str("╰───╯ ");
    }
    format!("{top}\n{mid}\n{bot}")
}

fn normalize_code(raw: &str) -> Option<String> {
    let trimmed = raw.trim();
    if trimmed.len() == 6 && trimmed.bytes().all(|byte| byte.is_ascii_digit()) {
        Some(trimmed.to_string())
    } else {
        None
    }
}

fn random_code() -> Result<String> {
    let mut code = String::with_capacity(6);
    let mut buf = [0u8; 32];
    while code.len() < 6 {
        getrandom::fill(&mut buf).map_err(|_| Error::new("io", "secure random is unavailable"))?;
        for byte in buf {
            if byte >= 250 {
                continue;
            }
            code.push(char::from(b'0' + (byte % 10)));
            if code.len() == 6 {
                break;
            }
        }
    }
    Ok(code)
}

fn unix_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn is_rfc1918(ip: Ipv4Addr) -> bool {
    let [a, b, _, _] = ip.octets();
    a == 10 || (a == 172 && (16..32).contains(&b)) || (a == 192 && b == 168)
}

fn is_link_local_v4(ip: Ipv4Addr) -> bool {
    let [a, b, _, _] = ip.octets();
    a == 169 && b == 254
}

fn is_ula(ip: Ipv6Addr) -> bool {
    (ip.segments()[0] & 0xfe00) == 0xfc00
}

fn parse_advertised_ip(text: &str) -> Option<IpAddr> {
    let trimmed = text.trim().trim_matches(|ch| ch == '[' || ch == ']');
    trimmed.parse().ok()
}

fn advertised_has_ip(advertised: &[String], ip: IpAddr) -> bool {
    advertised
        .iter()
        .any(|text| parse_advertised_ip(text) == Some(ip))
}

fn tailnet_advertised(advertised: &[String]) -> bool {
    advertised.iter().any(|text| {
        parse_advertised_ip(text)
            .map(|ip| is_tailnet_ip(&ip.to_string()))
            .unwrap_or(false)
    })
}

fn is_redeem_listen_ip(ip: IpAddr, advertised: &[String]) -> bool {
    if ip.is_loopback() || ip.is_unspecified() || ip.is_multicast() {
        return false;
    }
    match ip {
        IpAddr::V4(v4) if v4.is_broadcast() => false,
        IpAddr::V4(v4) if is_rfc1918(v4) || is_link_local_v4(v4) => true,
        IpAddr::V6(v6) if is_ula(v6) || v6.is_unicast_link_local() => true,
        other if is_tailnet_ip(&other.to_string()) && advertised_has_ip(advertised, other) => true,
        _ => false,
    }
}

fn is_redeem_peer(ip: IpAddr, advertised: &[String], allow_loopback: bool) -> bool {
    // sing-box userspace mode delivers tailnet connections to 8741 as
    // 127.0.0.1. Keep allowing loopback; do not add source filters.
    // Tailscale ACL, the 90s code lifetime, and the 5-wrong-tries void
    // are what keep that path safe.
    if allow_loopback && ip.is_loopback() {
        return true;
    }
    if ip.is_loopback() || ip.is_unspecified() || ip.is_multicast() {
        return false;
    }
    match ip {
        IpAddr::V4(v4) if v4.is_broadcast() => false,
        IpAddr::V4(v4) if is_rfc1918(v4) || is_link_local_v4(v4) => true,
        IpAddr::V6(v6) if is_ula(v6) || v6.is_unicast_link_local() => true,
        other if is_tailnet_ip(&other.to_string()) && tailnet_advertised(advertised) => true,
        _ => false,
    }
}

fn rank_listen(ip: IpAddr) -> u8 {
    match ip {
        IpAddr::V4(v4) if is_rfc1918(v4) => 0,
        IpAddr::V4(v4) if is_tailnet_ip(&v4.to_string()) => 1,
        IpAddr::V6(v6) if is_ula(v6) && !is_tailnet_ip(&v6.to_string()) => 2,
        IpAddr::V6(v6) if is_tailnet_ip(&v6.to_string()) => 3,
        IpAddr::V4(v4) if is_link_local_v4(v4) => 4,
        IpAddr::V6(v6) if v6.is_unicast_link_local() => 5,
        _ => 6,
    }
}

fn is_link_local_unicast(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => is_link_local_v4(v4),
        IpAddr::V6(v6) => v6.is_unicast_link_local(),
    }
}

/// Addresses to print for the phone. Link-local (fe80, 169.254) is omitted:
/// those interfaces are numerous and are not what the phone should type.
/// The process still listens on the wildcard sockets, not on these IPs.
fn banner_targets(local: &[BindAddr], advertised: &[String], port: u16) -> Vec<String> {
    let mut seen = HashSet::new();
    let mut ips = Vec::new();
    let mut push = |ip: IpAddr| {
        if ip.is_unspecified() || ip.is_loopback() || is_link_local_unicast(ip) {
            return;
        }
        if !is_redeem_listen_ip(ip, advertised) {
            return;
        }
        if seen.insert(ip) {
            ips.push(ip);
        }
    };
    for text in advertised {
        if let Some(ip) = parse_advertised_ip(text) {
            push(ip);
        }
    }
    for addr in select_bind_addrs(local, advertised) {
        push(addr.ip);
    }
    ips.into_iter()
        .map(|ip| format_target(SocketAddr::new(ip, port)))
        .collect()
}

fn select_bind_addrs(local: &[BindAddr], advertised: &[String]) -> Vec<BindAddr> {
    let mut allowed: Vec<BindAddr> = local
        .iter()
        .filter(|addr| is_redeem_listen_ip(addr.ip, advertised))
        .cloned()
        .collect();
    allowed.sort_by_key(|addr| {
        let advertised_hit = if advertised_has_ip(advertised, addr.ip) {
            0
        } else {
            1
        };
        (advertised_hit, rank_listen(addr.ip), addr.ip.to_string())
    });
    allowed
}

fn local_bind_candidates() -> Vec<BindAddr> {
    let mut out = Vec::new();
    unsafe {
        let mut ifap: *mut libc::ifaddrs = std::ptr::null_mut();
        if libc::getifaddrs(&mut ifap) != 0 {
            return out;
        }
        let mut ptr = ifap;
        while !ptr.is_null() {
            let ifa = &*ptr;
            let flags = ifa.ifa_flags as u32;
            if flags & (libc::IFF_UP as u32) == 0
                || flags & (libc::IFF_LOOPBACK as u32) != 0
                || ifa.ifa_addr.is_null()
            {
                ptr = ifa.ifa_next;
                continue;
            }
            let family = (*ifa.ifa_addr).sa_family as i32;
            if family == libc::AF_INET {
                let sin = &*(ifa.ifa_addr as *const libc::sockaddr_in);
                let ip = Ipv4Addr::from(u32::from_be(sin.sin_addr.s_addr));
                if !ip.is_loopback()
                    && !ip.is_unspecified()
                    && !ip.is_broadcast()
                    && !ip.is_multicast()
                {
                    out.push(BindAddr { ip: IpAddr::V4(ip) });
                }
            } else if family == libc::AF_INET6 {
                let sin6 = &*(ifa.ifa_addr as *const libc::sockaddr_in6);
                let ip = Ipv6Addr::from(sin6.sin6_addr.s6_addr);
                if !ip.is_loopback() && !ip.is_unspecified() && !ip.is_multicast() {
                    out.push(BindAddr { ip: IpAddr::V6(ip) });
                }
            }
            ptr = ifa.ifa_next;
        }
        libc::freeifaddrs(ifap);
    }
    let mut seen = HashSet::new();
    out.retain(|addr| seen.insert(addr.ip));
    out
}

enum SockStorage {
    V4(libc::sockaddr_in),
    V6(libc::sockaddr_in6),
}

struct SockSpec {
    domain: libc::c_int,
    v6_only: bool,
    len: libc::socklen_t,
    storage: SockStorage,
}

impl SockSpec {
    fn from_addr(addr: SocketAddr) -> Self {
        match addr {
            SocketAddr::V4(v4) => {
                let mut storage: libc::sockaddr_in = unsafe { std::mem::zeroed() };
                storage.sin_family = libc::AF_INET as libc::sa_family_t;
                storage.sin_port = v4.port().to_be();
                storage.sin_addr = libc::in_addr {
                    s_addr: u32::from(*v4.ip()).to_be(),
                };
                #[cfg(target_os = "macos")]
                {
                    storage.sin_len = std::mem::size_of::<libc::sockaddr_in>() as u8;
                }
                Self {
                    domain: libc::AF_INET,
                    v6_only: false,
                    len: std::mem::size_of::<libc::sockaddr_in>() as libc::socklen_t,
                    storage: SockStorage::V4(storage),
                }
            }
            SocketAddr::V6(v6) => {
                let mut storage: libc::sockaddr_in6 = unsafe { std::mem::zeroed() };
                storage.sin6_family = libc::AF_INET6 as libc::sa_family_t;
                storage.sin6_port = v6.port().to_be();
                storage.sin6_addr = libc::in6_addr {
                    s6_addr: v6.ip().octets(),
                };
                storage.sin6_scope_id = v6.scope_id();
                #[cfg(target_os = "macos")]
                {
                    storage.sin6_len = std::mem::size_of::<libc::sockaddr_in6>() as u8;
                }
                Self {
                    domain: libc::AF_INET6,
                    v6_only: true,
                    len: std::mem::size_of::<libc::sockaddr_in6>() as libc::socklen_t,
                    storage: SockStorage::V6(storage),
                }
            }
        }
    }

    fn ptr(&self) -> *const libc::sockaddr {
        match &self.storage {
            SockStorage::V4(value) => value as *const libc::sockaddr_in as *const libc::sockaddr,
            SockStorage::V6(value) => value as *const libc::sockaddr_in6 as *const libc::sockaddr,
        }
    }
}

fn bind_listener(addr: SocketAddr) -> Result<TcpListener> {
    let spec = SockSpec::from_addr(addr);
    let fd = unsafe { libc::socket(spec.domain, libc::SOCK_STREAM, 0) };
    if fd < 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    let yes: libc::c_int = 1;
    let bound = unsafe {
        libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_REUSEADDR,
            &yes as *const libc::c_int as *const libc::c_void,
            std::mem::size_of_val(&yes) as libc::socklen_t,
        );
        let flags = libc::fcntl(fd, libc::F_GETFD);
        if flags >= 0 {
            libc::fcntl(fd, libc::F_SETFD, flags | libc::FD_CLOEXEC);
        }
        if spec.v6_only {
            libc::setsockopt(
                fd,
                libc::IPPROTO_IPV6,
                libc::IPV6_V6ONLY,
                &yes as *const libc::c_int as *const libc::c_void,
                std::mem::size_of_val(&yes) as libc::socklen_t,
            );
        }
        let rc = libc::bind(fd, spec.ptr(), spec.len);
        if rc != 0 {
            let err = std::io::Error::last_os_error();
            libc::close(fd);
            return Err(err.into());
        }
        if libc::listen(fd, 16) != 0 {
            let err = std::io::Error::last_os_error();
            libc::close(fd);
            return Err(err.into());
        }
        TcpListener::from_raw_fd(fd)
    };
    bound.set_nonblocking(true)?;
    Ok(bound)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::hostinfo::HostFacts;
    use crate::role::Role;

    fn grant_with(id: &str, dk: &str) -> Grant {
        Grant {
            id: id.into(),
            name: "phone".into(),
            role: Role::Controller,
            fingerprint: dk.into(),
            key_type: "ssh-ed25519".into(),
            key: "AAAA".into(),
            comment: String::new(),
            created_at: 0,
            push: None,
        }
    }

    fn grant() -> Grant {
        grant_with("ab".repeat(16).as_str(), "SHA256:devicekey")
    }

    fn facts() -> HostFacts {
        HostFacts {
            name: "studio".into(),
            user: "misaka".into(),
            port: 22,
            addrs: vec!["192.168.1.4".into(), "100.64.0.8".into()],
            hk: vec!["SHA256:hostkeyone".into(), "SHA256:hostkeytwo".into()],
            fqdn: None,
            bridge: "/usr/local/bin/luvia-host".into(),
            host_id: "cd".repeat(16),
        }
    }

    fn policy(addrs: &[&str]) -> PeerPolicy {
        PeerPolicy {
            advertised: addrs.iter().map(|addr| (*addr).to_string()).collect(),
            allow_loopback: false,
        }
    }

    fn lan() -> IpAddr {
        "192.168.1.20".parse().unwrap()
    }

    fn table_with(now: u64) -> (TicketTable, Minted) {
        let mut table = TicketTable {
            tickets: Vec::new(),
            end: None,
        };
        let minted = table
            .mint(&grant(), &facts(), Duration::from_secs(90), now)
            .unwrap();
        (table, minted)
    }

    fn wrong_code(real: &str) -> String {
        if real == "000000" {
            "000001".into()
        } else {
            "000000".into()
        }
    }

    #[test]
    fn mint_returns_the_same_luvia1_encode_and_success_burns_the_ticket() {
        let now = 1_000_000_u64;
        let (mut table, minted) = table_with(now);
        let expected = PairingPayload::from_grant(&grant(), &facts())
            .unwrap()
            .encode()
            .unwrap();
        assert_eq!(minted.luvia1, expected);
        assert_eq!(minted.code.len(), 6);
        assert!(minted.code.bytes().all(|byte| byte.is_ascii_digit()));
        let got = table
            .redeem(
                &minted.code,
                "SHA256:devicekey",
                lan(),
                now,
                &policy(&["192.168.1.4"]),
            )
            .unwrap();
        assert_eq!(got, expected);
        assert_eq!(
            PairingPayload::decode(&got).unwrap().device_key,
            "SHA256:devicekey"
        );
        let again = table
            .redeem(
                &minted.code,
                "SHA256:devicekey",
                lan(),
                now,
                &policy(&["192.168.1.4"]),
            )
            .unwrap_err();
        assert_eq!(again, RedeemError::InvalidCode);
    }

    #[test]
    fn ttl_bounds_and_expiry() {
        assert_eq!(DEFAULT_SHORT_TTL_SECS, 90);
        assert!(validate_ttl_secs(59).is_err());
        assert!(validate_ttl_secs(121).is_err());
        assert!(validate_ttl_secs(60).is_ok());
        assert!(validate_ttl_secs(120).is_ok());
        let now = 5_000_000_u64;
        let (mut alive, minted) = table_with(now);
        assert!(alive
            .redeem(
                &minted.code,
                "SHA256:devicekey",
                lan(),
                now + 89_999,
                &policy(&["192.168.1.4"])
            )
            .is_ok());
        let (mut dead, minted) = table_with(now);
        assert_eq!(
            dead.redeem(
                &minted.code,
                "SHA256:devicekey",
                lan(),
                now + 90_000,
                &policy(&["192.168.1.4"])
            )
            .unwrap_err(),
            RedeemError::Expired
        );
    }

    #[test]
    fn wrong_dk_is_rejected_and_five_failures_burn_the_grant_ticket() {
        let now = 1_000_u64;
        let (mut table, minted) = table_with(now);
        let policy = policy(&["192.168.1.4"]);
        assert_eq!(
            table
                .redeem(&minted.code, "SHA256:other", lan(), now, &policy)
                .unwrap_err(),
            RedeemError::DkMismatch
        );
        assert!(table
            .redeem(&minted.code, "SHA256:devicekey", lan(), now, &policy)
            .is_ok());

        let (mut table, minted) = table_with(now);
        for _ in 0..4 {
            assert_eq!(
                table
                    .redeem(&minted.code, "SHA256:other", lan(), now, &policy)
                    .unwrap_err(),
                RedeemError::DkMismatch
            );
        }
        assert_eq!(
            table
                .redeem(&minted.code, "SHA256:other", lan(), now, &policy)
                .unwrap_err(),
            RedeemError::Exhausted
        );
        assert_eq!(
            table
                .redeem(&minted.code, "SHA256:devicekey", lan(), now, &policy)
                .unwrap_err(),
            RedeemError::InvalidCode
        );
    }

    #[test]
    fn five_misses_invalidate_the_ticket_so_the_space_cannot_be_exhausted() {
        let now = 1_000_u64;
        let (mut table, minted) = table_with(now);
        let policy = policy(&["192.168.1.4"]);
        let wrong = wrong_code(&minted.code);
        for _ in 0..4 {
            assert_eq!(
                table
                    .redeem(&wrong, "SHA256:devicekey", lan(), now, &policy)
                    .unwrap_err(),
                RedeemError::InvalidCode
            );
        }
        assert_eq!(
            table
                .redeem(&wrong, "SHA256:devicekey", lan(), now, &policy)
                .unwrap_err(),
            RedeemError::Exhausted
        );
        assert_eq!(
            table
                .redeem(&minted.code, "SHA256:devicekey", lan(), now, &policy)
                .unwrap_err(),
            RedeemError::InvalidCode
        );
    }

    #[test]
    fn a_miss_counts_against_every_live_ticket() {
        let now = 1_000_u64;
        let mut table = TicketTable {
            tickets: Vec::new(),
            end: None,
        };
        let first = table
            .mint(
                &grant_with(&"aa".repeat(16), "SHA256:one"),
                &facts(),
                Duration::from_secs(60),
                now,
            )
            .unwrap();
        let second = table
            .mint(
                &grant_with(&"bb".repeat(16), "SHA256:two"),
                &facts(),
                Duration::from_secs(60),
                now,
            )
            .unwrap();
        let policy = policy(&["192.168.1.4"]);
        let wrong = if first.code != "111111" && second.code != "111111" {
            "111111"
        } else {
            "222222"
        };
        for _ in 0..4 {
            assert_eq!(
                table
                    .redeem(wrong, "SHA256:one", lan(), now, &policy)
                    .unwrap_err(),
                RedeemError::InvalidCode
            );
        }
        assert_eq!(
            table
                .redeem(wrong, "SHA256:one", lan(), now, &policy)
                .unwrap_err(),
            RedeemError::Exhausted
        );
        assert!(table
            .redeem(&first.code, "SHA256:one", lan(), now, &policy)
            .is_err());
        assert!(table
            .redeem(&second.code, "SHA256:two", lan(), now, &policy)
            .is_err());
    }

    #[test]
    fn remint_replaces_the_previous_code_for_that_grant() {
        let now = 1_000_u64;
        let mut table = TicketTable {
            tickets: Vec::new(),
            end: None,
        };
        let policy = policy(&["192.168.1.4"]);
        let first = table
            .mint(&grant(), &facts(), Duration::from_secs(90), now)
            .unwrap();
        let mut second = table
            .mint(&grant(), &facts(), Duration::from_secs(90), now)
            .unwrap();
        let mut guard = 0;
        while second.code == first.code && guard < 4 {
            second = table
                .mint(&grant(), &facts(), Duration::from_secs(90), now)
                .unwrap();
            guard += 1;
        }
        assert_ne!(first.code, second.code);
        assert_eq!(
            table
                .redeem(&first.code, "SHA256:devicekey", lan(), now, &policy)
                .unwrap_err(),
            RedeemError::InvalidCode
        );
        assert_eq!(
            table
                .redeem(&second.code, "SHA256:devicekey", lan(), now, &policy)
                .unwrap(),
            second.luvia1
        );
    }

    #[test]
    fn public_peers_do_not_consume_attempts() {
        let now = 1_000_u64;
        let (mut table, minted) = table_with(now);
        let policy = policy(&["8.8.8.8", "192.168.1.4"]);
        let public_ip: IpAddr = "8.8.8.8".parse().unwrap();
        for _ in 0..10 {
            assert_eq!(
                table
                    .redeem(&minted.code, "SHA256:devicekey", public_ip, now, &policy)
                    .unwrap_err(),
                RedeemError::PeerNotAllowed
            );
        }
        assert!(table
            .redeem(&minted.code, "SHA256:devicekey", lan(), now, &policy)
            .is_ok());
    }

    #[test]
    fn listen_and_peer_policy() {
        let advertised = vec!["192.168.1.4".to_string(), "100.64.0.1".to_string()];
        assert!(is_redeem_listen_ip(
            "10.1.2.3".parse().unwrap(),
            &advertised
        ));
        assert!(is_redeem_listen_ip(
            "172.16.0.1".parse().unwrap(),
            &advertised
        ));
        assert!(is_redeem_listen_ip(
            "172.31.255.255".parse().unwrap(),
            &advertised
        ));
        assert!(!is_redeem_listen_ip(
            "172.15.255.255".parse().unwrap(),
            &advertised
        ));
        assert!(!is_redeem_listen_ip(
            "172.32.0.1".parse().unwrap(),
            &advertised
        ));
        assert!(is_redeem_listen_ip(
            "192.168.0.1".parse().unwrap(),
            &advertised
        ));
        assert!(is_redeem_listen_ip(
            "169.254.1.1".parse().unwrap(),
            &advertised
        ));
        assert!(is_redeem_listen_ip(
            "fd00::1234".parse().unwrap(),
            &advertised
        ));
        assert!(is_redeem_listen_ip("fe80::1".parse().unwrap(), &advertised));
        assert!(is_redeem_listen_ip(
            "fd7a:115c:a1e0::1".parse().unwrap(),
            &advertised
        ));
        assert!(is_redeem_listen_ip(
            "100.64.0.1".parse().unwrap(),
            &advertised
        ));
        assert!(!is_redeem_listen_ip(
            "100.64.0.2".parse().unwrap(),
            &advertised
        ));
        assert!(!is_redeem_listen_ip(
            "8.8.8.8".parse().unwrap(),
            &advertised
        ));
        assert!(!is_redeem_listen_ip(
            "2001:db8::1".parse().unwrap(),
            &advertised
        ));
        assert!(!is_redeem_listen_ip(
            "127.0.0.1".parse().unwrap(),
            &advertised
        ));
        assert!(!is_redeem_listen_ip("0.0.0.0".parse().unwrap(), &[]));

        assert!(is_redeem_peer(
            "192.168.1.9".parse().unwrap(),
            &advertised,
            false
        ));
        assert!(is_redeem_peer(
            "fe80::abcd".parse().unwrap(),
            &advertised,
            false
        ));
        assert!(is_redeem_peer(
            "fd12::1".parse().unwrap(),
            &advertised,
            false
        ));
        assert!(is_redeem_peer(
            "100.99.1.2".parse().unwrap(),
            &advertised,
            false
        ));
        assert!(!is_redeem_peer(
            "100.99.1.2".parse().unwrap(),
            &["192.168.1.4".to_string()],
            false
        ));
        assert!(!is_redeem_peer(
            "8.8.8.8".parse().unwrap(),
            &advertised,
            false
        ));
        assert!(!is_redeem_peer(
            "127.0.0.1".parse().unwrap(),
            &advertised,
            false
        ));
        assert!(is_redeem_peer(
            "127.0.0.1".parse().unwrap(),
            &advertised,
            true
        ));

        let local = vec![
            BindAddr {
                ip: "172.17.0.1".parse().unwrap(),
            },
            BindAddr {
                ip: "192.168.1.4".parse().unwrap(),
            },
            BindAddr {
                ip: "100.64.0.1".parse().unwrap(),
            },
            BindAddr {
                ip: "8.8.8.8".parse().unwrap(),
            },
            BindAddr {
                ip: "169.254.1.1".parse().unwrap(),
            },
        ];
        let selected = select_bind_addrs(&local, &advertised);
        let ips: Vec<String> = selected.iter().map(|addr| addr.ip.to_string()).collect();
        assert_eq!(
            ips,
            vec![
                "192.168.1.4".to_string(),
                "100.64.0.1".to_string(),
                "172.17.0.1".to_string(),
                "169.254.1.1".to_string(),
            ]
        );
        assert!(select_bind_addrs(
            &[BindAddr {
                ip: "8.8.8.8".parse().unwrap(),
            }],
            &["8.8.8.8".into()],
        )
        .is_empty());
    }

    #[test]
    fn banner_targets_skip_link_local() {
        let local = vec![
            BindAddr {
                ip: "192.168.1.83".parse().unwrap(),
            },
            BindAddr {
                ip: "172.18.0.1".parse().unwrap(),
            },
            BindAddr {
                ip: "169.254.1.1".parse().unwrap(),
            },
            BindAddr {
                ip: "fe80::1".parse().unwrap(),
            },
        ];
        let advertised = vec!["192.168.1.83".to_string()];
        let targets = banner_targets(&local, &advertised, 8741);
        assert_eq!(
            targets.first().map(String::as_str),
            Some("192.168.1.83:8741")
        );
        assert!(targets.iter().any(|target| target == "172.18.0.1:8741"));
        assert!(targets
            .iter()
            .all(|target| { !target.contains("fe80") && !target.starts_with("169.254") }));
    }

    #[test]
    fn banner_shows_each_digit() {
        let banner = render_short_code("482197");
        for ch in ['4', '8', '2', '1', '9', '7'] {
            assert!(banner.contains(ch));
        }
        assert!(banner.contains('╭'));
        assert!(banner.contains("│ 4 │"));
        assert!(banner.contains("│ 7 │"));
    }

    #[test]
    fn local_candidates_are_not_loopback() {
        for addr in local_bind_candidates() {
            assert!(!addr.ip.is_loopback(), "{}", addr.ip);
            assert!(!addr.ip.is_unspecified(), "{}", addr.ip);
        }
        for addr in select_bind_addrs(&local_bind_candidates(), &[]) {
            assert!(is_redeem_listen_ip(addr.ip, &[]), "{}", addr.ip);
        }
    }

    fn post(port: u16, body: &str) -> (u16, String) {
        let url = format!("http://127.0.0.1:{port}{REDEEM_PATH}");
        let agent = ureq::AgentBuilder::new()
            .timeout(Duration::from_secs(3))
            .build();
        match agent
            .post(&url)
            .set("Content-Type", "application/json")
            .send_string(body)
        {
            Ok(response) => (
                response.status(),
                response.into_string().unwrap_or_default(),
            ),
            Err(ureq::Error::Status(code, response)) => {
                (code, response.into_string().unwrap_or_default())
            }
            Err(error) => panic!("redeem request failed: {error}"),
        }
    }

    #[test]
    fn wildcard_bind_redeems_within_a_second() {
        let table = Arc::new(Mutex::new(TicketTable {
            tickets: Vec::new(),
            end: None,
        }));
        let minted = table
            .lock()
            .unwrap()
            .mint(&grant(), &facts(), Duration::from_secs(90), unix_ms())
            .unwrap();
        let server = serve_wildcard(table, 0, &["192.168.1.4".into()], true).unwrap();
        let port = server
            .bound
            .iter()
            .find(|addr| addr.is_ipv4())
            .map(|addr| addr.port())
            .unwrap();
        let body = format!(r#"{{"code":"{}","dk":"SHA256:devicekey"}}"#, minted.code);
        let started = std::time::Instant::now();
        let (status, text) = post(port, &body);
        let elapsed = started.elapsed();
        assert!(
            elapsed < Duration::from_secs(1),
            "redeem took {elapsed:?}; body {text}"
        );
        assert_eq!(status, 200, "{text}");
        assert!(text.contains("luvia2"));
    }

    #[test]
    fn http_redeem_returns_luvia1_once() {
        let table = Arc::new(Mutex::new(TicketTable {
            tickets: Vec::new(),
            end: None,
        }));
        let minted = table
            .lock()
            .unwrap()
            .mint(&grant(), &facts(), Duration::from_secs(90), unix_ms())
            .unwrap();
        let server = serve_explicit(
            table,
            "127.0.0.1:0".parse().unwrap(),
            true,
            &["192.168.1.4".into()],
        )
        .unwrap();
        let port = server.bound[0].port();
        let body = format!(r#"{{"code":"{}","dk":"SHA256:devicekey"}}"#, minted.code);
        let (status, text) = post(port, &body);
        assert_eq!(status, 200, "{text}");
        let value: serde_json::Value = serde_json::from_str(&text).unwrap();
        assert_eq!(value["luvia2"].as_str().unwrap(), minted.luvia1);
        let (status, text) = post(port, &body);
        assert_eq!(status, 404, "{text}");
        assert!(text.contains("invalid_code"));
    }

    #[test]
    fn http_loopback_is_rejected_until_the_policy_allows_it_and_does_not_burn() {
        let table = Arc::new(Mutex::new(TicketTable {
            tickets: Vec::new(),
            end: None,
        }));
        let minted = table
            .lock()
            .unwrap()
            .mint(&grant(), &facts(), Duration::from_secs(90), unix_ms())
            .unwrap();
        let body = format!(r#"{{"code":"{}","dk":"SHA256:devicekey"}}"#, minted.code);
        let closed = serve_explicit(
            table.clone(),
            "127.0.0.1:0".parse().unwrap(),
            false,
            &["192.168.1.4".into()],
        )
        .unwrap();
        let (status, text) = post(closed.bound[0].port(), &body);
        assert_eq!(status, 403, "{text}");
        assert!(text.contains("peer_not_allowed"));
        drop(closed);
        let open = serve_explicit(
            table,
            "127.0.0.1:0".parse().unwrap(),
            true,
            &["192.168.1.4".into()],
        )
        .unwrap();
        let (status, text) = post(open.bound[0].port(), &body);
        assert_eq!(status, 200, "{text}");
    }

    #[test]
    fn http_wrong_dk_expired_and_attempt_cap() {
        let table = Arc::new(Mutex::new(TicketTable {
            tickets: Vec::new(),
            end: None,
        }));
        let minted = table
            .lock()
            .unwrap()
            .mint(&grant(), &facts(), Duration::from_secs(90), unix_ms())
            .unwrap();
        let server = serve_explicit(table, "127.0.0.1:0".parse().unwrap(), true, &[]).unwrap();
        let port = server.bound[0].port();
        let (status, text) = post(
            port,
            &format!(r#"{{"code":"{}","dk":"SHA256:nope"}}"#, minted.code),
        );
        assert_eq!(status, 403, "{text}");
        assert!(text.contains("dk_mismatch"));
        drop(server);

        let expired = Arc::new(Mutex::new(TicketTable {
            tickets: Vec::new(),
            end: None,
        }));
        let minted = expired
            .lock()
            .unwrap()
            .mint(&grant(), &facts(), Duration::from_secs(60), 0)
            .unwrap();
        let server = serve_explicit(expired, "127.0.0.1:0".parse().unwrap(), true, &[]).unwrap();
        let (status, text) = post(
            server.bound[0].port(),
            &format!(r#"{{"code":"{}","dk":"SHA256:devicekey"}}"#, minted.code),
        );
        assert_eq!(status, 410, "{text}");
        assert!(text.contains("expired"));
        drop(server);

        let capped = Arc::new(Mutex::new(TicketTable {
            tickets: Vec::new(),
            end: None,
        }));
        let minted = capped
            .lock()
            .unwrap()
            .mint(&grant(), &facts(), Duration::from_secs(90), unix_ms())
            .unwrap();
        let server = serve_explicit(capped, "127.0.0.1:0".parse().unwrap(), true, &[]).unwrap();
        let port = server.bound[0].port();
        let wrong = wrong_code(&minted.code);
        let mut saw_exhausted = false;
        for _ in 0..5 {
            let (status, text) = post(
                port,
                &format!(r#"{{"code":"{wrong}","dk":"SHA256:devicekey"}}"#),
            );
            assert!(status == 404 || status == 410, "{status} {text}");
            if text.contains("exhausted") {
                saw_exhausted = true;
            }
        }
        assert!(saw_exhausted);
        let (status, text) = post(
            port,
            &format!(r#"{{"code":"{}","dk":"SHA256:devicekey"}}"#, minted.code),
        );
        assert_ne!(status, 200, "{text}");
        assert!(!text.contains("luvia1"));
    }
}
