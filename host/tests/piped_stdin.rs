use std::io::{BufRead, BufReader, Read, Write};
use std::process::{Command, Stdio};

use ed25519_dalek::Signer;
use ed25519_dalek::SigningKey;
use luvia_host::auth::{self, auth_message};

#[test]
fn bridge_piped_stdin_does_not_fail_enotsock() {
    let dir = tempfile::tempdir().unwrap();
    let luvia_home = dir.path().join("luvia");
    let host_dir = luvia_home.join("host");
    std::fs::create_dir_all(&host_dir).unwrap();
    let id = "ab".repeat(16);
    let host_id = "cd".repeat(16);
    std::fs::write(host_dir.join("host_id"), format!("{host_id}\n")).unwrap();
    let signing = SigningKey::from_bytes(&[8u8; 32]);
    let pubkey = auth::openssh_pubkey_from_raw(&signing.verifying_key().to_bytes());
    let grants = serde_json::json!({
        "devices": [{
            "device_id": id,
            "name": "phone",
            "pubkey": pubkey,
            "role": "observer",
            "created": 0
        }]
    });
    std::fs::write(
        host_dir.join("grants.json"),
        serde_json::to_vec_pretty(&grants).unwrap(),
    )
    .unwrap();

    let mut child = Command::new(env!("CARGO_BIN_EXE_luvia-host"))
        .arg("bridge")
        .env("HOME", dir.path())
        .env("LUVIA_HOME", &luvia_home)
        .env("LUVIA_AUTHORIZED_KEYS", dir.path().join("authorized_keys"))
        .env("LUVUS_HOME", dir.path().join("luvus"))
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn luvia-host bridge");

    let mut stdin = child.stdin.take().expect("child stdin");
    let mut stdout = BufReader::new(child.stdout.take().expect("child stdout"));
    let mut hello = String::new();
    stdout.read_line(&mut hello).expect("read hello");
    let parsed = auth::parse_hello(hello.trim_end()).expect("hello frame");
    assert_eq!(parsed.host_id, host_id);
    assert_eq!(parsed.nonce.len(), 32);
    let sig = signing
        .sign(&auth_message(&parsed.host_id, &parsed.nonce, &id))
        .to_bytes();
    stdin
        .write_all(&auth::prove_frame(&id, &sig).unwrap())
        .unwrap();
    stdin
        .write_all(br#"{"version":1,"operation":"discover"}"#)
        .unwrap();
    stdin.write_all(b"\n").unwrap();
    drop(stdin);

    let mut rest = Vec::new();
    stdout.read_to_end(&mut rest).expect("read bridge stdout");
    let status = child.wait().expect("wait for luvia-host");
    let mut err_buf = Vec::new();
    if let Some(mut stderr) = child.stderr.take() {
        stderr.read_to_end(&mut err_buf).ok();
    }
    let stderr = String::from_utf8_lossy(&err_buf);
    assert!(
        !stderr.contains("Socket operation on non-socket") && !stderr.contains("os error 38"),
        "piped stdin must not hit ENOTSOCK: {stderr}"
    );
    let stdout = String::from_utf8_lossy(&rest);
    assert!(
        stdout.contains("sessions") || status.success(),
        "bridge should complete a discover over a pipe: stdout={stdout:?} stderr={stderr:?}"
    );
}
