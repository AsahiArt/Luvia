use std::io::Write;
use std::process::{Command, Stdio};

#[test]
fn bridge_piped_stdin_does_not_fail_enotsock() {
    let dir = tempfile::tempdir().unwrap();
    let luvia_home = dir.path().join("luvia");
    let devices = luvia_home.join("host").join("devices");
    std::fs::create_dir_all(&devices).unwrap();
    let id = "ab".repeat(16);
    let grant = format!(
        r#"{{"id":"{id}","name":"phone","role":"observer","fingerprint":"SHA256:test","key_type":"ssh-ed25519","key":"AAAA","comment":"","created_at":0}}"#
    );
    std::fs::write(devices.join(format!("{id}.json")), grant).unwrap();

    let mut child = Command::new(env!("CARGO_BIN_EXE_luvia-host"))
        .args(["bridge", "--device", &id])
        .env("HOME", dir.path())
        .env("LUVIA_HOME", &luvia_home)
        .env("LUVIA_AUTHORIZED_KEYS", dir.path().join("authorized_keys"))
        .env("LUVUS_HOME", dir.path().join("luvus"))
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn luvia-host bridge");

    {
        let mut stdin = child.stdin.take().expect("child stdin");
        stdin
            .write_all(br#"{"version":1,"operation":"discover"}"#)
            .unwrap();
        stdin.write_all(b"\n").unwrap();
    }

    let output = child.wait_with_output().expect("wait for luvia-host");
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(
        !stderr.contains("Socket operation on non-socket") && !stderr.contains("os error 38"),
        "piped stdin must not hit ENOTSOCK: {stderr}"
    );
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(
        stdout.contains("sessions") || output.status.success(),
        "bridge should complete a discover over a pipe: stdout={stdout:?} stderr={stderr:?}"
    );
}
