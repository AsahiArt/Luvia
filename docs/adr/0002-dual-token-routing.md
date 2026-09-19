# ADR 0002: The bridge mints a session token and an action token; neither reaches the Device

Status: accepted (2026-09-02)

## Context

On Luvus 0.13.2, `session.snapshot` and `events.subscribe` required `admin`
(empirical finding F1), yet the Device must never hold `admin`. At HEAD
(0.13.4) both are `required_scope = "read"` (`src/api/capabilities.rs:276-289`),
so the original forcing function is gone for new Hosts, but older Hosts remain
in the field and `uhp.token.create` itself stays `admin`.

## Decision

`luvia-host bridge` (`host/src/role.rs`, `host/src/uhp.rs`) mints ephemeral
tokens via `uhp.token.create` as the local owner:

- Session token: `read,admin`. Spent only on `session.snapshot` and
  `events.subscribe`. Minted only when capabilities still advertise those
  methods as `scope=admin` (Luvus older than 0.13.4).
- Action token: exactly the Role's scopes (Observer `read`; Controller
  `read,workspace,agent,terminal,orchestration`). Spent on every other method,
  and on snapshot/subscribe when no session token was minted.

The bridge routes by method name; the Device's frames carry no `auth` field
and cannot choose a token. Tokens are memory-only on the Host, TTL-bound,
and revoked on bridge exit.

## Alternatives rejected

- Give the Device a token directly: leaks a Luvus credential off-host and
  cannot be scoped below `read` for snapshot on 0.13.2.
- Use `luvus uhp access` (#231): it is not a transport. The gateway binds
  `127.0.0.1` on an ephemeral port (`src/uhp/gateway.rs:88`) and upstream
  states "Luvus does not bundle or select a remote transport"; a provider must
  forward the loopback endpoint over its own secure stream
  (`website/.../uhp/remote-access.mdx:84-89,120-123`). We would still need
  SSH around it. Its Control allow-list is `workspace/tab/pane.focus`,
  `agent.prompt`, `terminal.backend.control` plus safe reads
  (`src/uhp/gateway.rs:403-415`): no `agent.keys`, `agent.read`,
  `diff.note.*`, `task.*`, narrower than our Controller. It also hands the
  phone a bearer token, which the provider is told not to persist. Revisit
  only as a Windows-host shim where `luvia-host` fronts the loopback port
  instead of implementing named-pipe ownership checks.
- Drop the session token unconditionally now that F1 is fixed: breaks 0.13.2
  Hosts. Instead, skip minting it when live `uhp.capabilities` contracts show
  `session.snapshot` / `events.subscribe` as `scope=read` (0.13.4+).

## Consequences

- On 0.13.4+ Hosts the bridge mints only the action token and spends it on
  snapshot/subscribe. Older Hosts still get the `read,admin` session token.
- `uhp.token.*`, `config.get`, and other `admin` reads stay unreachable from
  the Device by construction.
