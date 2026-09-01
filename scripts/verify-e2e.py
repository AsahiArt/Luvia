#!/usr/bin/env python3
"""Real end-to-end verification: phone-equivalent client -> SSH -> luvia-host -> live Luvus.

Drives the actual shipped path rather than a mock: it pairs a throwaway device key
through `luvia-host pair`, connects over real SSH to localhost so sshd runs the forced
command, speaks the bridge prelude and then UHP, and asserts both that the happy path
works and that the role gating denies what it must.

Side effects, all reverted in `finally`: writes a managed line to ~/.ssh/authorized_keys
(via `luvia-host pair`, which is the product's designed behaviour) and creates a grant
under the luvia state dir. The authorized_keys file is backed up byte-for-byte first.
"""

from __future__ import annotations

import base64
import json
import os
import select
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
AUTHORIZED_KEYS = Path.home() / ".ssh" / "authorized_keys"

failures: list[str] = []
checks = 0


def check(ok: bool, label: str, detail: str = "") -> bool:
    global checks
    checks += 1
    if ok:
        print(f"  PASS  {label}")
    else:
        print(f"  FAIL  {label}" + (f"\n        {detail}" if detail else ""))
        failures.append(label)
    return ok


def phase(title: str) -> None:
    print(f"\n=== {title} ===")


def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


class Bridge:
    """One SSH connection to the forced command, framed as LF-terminated JSON."""

    def __init__(self, key_path: Path, user: str, port: int) -> None:
        self._err = tempfile.TemporaryFile(mode="w+b")
        self._buf = b""
        self.proc = subprocess.Popen(
            [
                "ssh",
                # No remote command is given, so ssh would request a PTY by
                # default. Terminal echo would feed every request we write back
                # to us as if it were a response, and the line discipline would
                # rewrite the framing.
                "-T",
                "-i", str(key_path),
                "-p", str(port),
                "-o", "IdentitiesOnly=yes",
                "-o", "StrictHostKeyChecking=no",
                "-o", "UserKnownHostsFile=/dev/null",
                "-o", "LogLevel=ERROR",
                "-o", "BatchMode=yes",
                f"{user}@localhost",
            ],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            # A pipe would deadlock: reading it blocks until the child exits, but
            # the bridge deliberately stays alive for the whole session, and the
            # failure-detail strings are built eagerly at every check() call.
            stderr=self._err,
            bufsize=0,
        )

    def send(self, obj: dict) -> None:
        assert self.proc.stdin
        self.proc.stdin.write((json.dumps(obj) + "\n").encode())
        self.proc.stdin.flush()

    def recv(self, timeout: float = 10.0) -> dict | None:
        """One frame, or None on EOF. Raises TimeoutError rather than hanging:
        a product bug must surface as a failed check, not as a stuck run."""
        assert self.proc.stdout
        fd = self.proc.stdout.fileno()
        deadline = time.time() + timeout
        while True:
            line, _, rest = self._buf.partition(b"\n")
            if rest or self._buf.endswith(b"\n"):
                self._buf = rest
                text = line.strip()
                if text:
                    return json.loads(text)
                continue
            remaining = deadline - time.time()
            if remaining <= 0:
                raise TimeoutError("no frame within timeout")
            if not select.select([fd], [], [], remaining)[0]:
                continue
            chunk = os.read(fd, 65536)
            if not chunk:
                return None
            self._buf += chunk

    def stderr(self) -> str:
        try:
            self._err.flush()
            self._err.seek(0)
            return self._err.read().decode(errors="replace")
        except Exception:
            return ""

    def close(self) -> None:
        for stream in (self.proc.stdin,):
            try:
                if stream:
                    stream.close()
            except Exception:
                pass
        try:
            self.proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        try:
            self._err.close()
        except Exception:
            pass

    def __enter__(self) -> "Bridge":
        return self

    def __exit__(self, *exc) -> None:
        self.close()


def open_session(bridge: Bridge, session: str = "default") -> dict | None:
    bridge.send({"version": 1, "operation": "open", "session": session})
    return bridge.recv()


def decode_pairing_code(code: str) -> dict:
    prefix, _, body = code.partition(":")
    assert prefix.lower() == "luvia1", f"unexpected prefix {prefix!r}"
    padded = body + "=" * (-len(body) % 4)
    return json.loads(base64.urlsafe_b64decode(padded).decode())


def main() -> int:
    user = os.environ.get("USER") or os.getlogin()
    backup = None
    device_id = None
    host_bin = REPO / "target" / "release" / "luvia-host"
    tmp = Path(tempfile.mkdtemp(prefix="luvia-e2e-"))

    try:
        phase("Preflight")
        check((Path.home() / ".luvus" / "luvus.sock").exists(),
              "live Luvus socket present")
        probe = run(["nc", "-z", "localhost", "22"])
        if not check(probe.returncode == 0, "localhost sshd reachable"):
            print("\nEnable Remote Login (System Settings > General > Sharing) to run this.")
            return 1

        phase("Build luvia-host")
        build = run(["cargo", "build", "--release", "-p", "luvia-host"], cwd=REPO)
        if not check(build.returncode == 0, "cargo build --release -p luvia-host",
                     build.stderr[-3000:]):
            return 1
        check(host_bin.exists(), "release binary exists")

        phase("Pair a throwaway device key")
        key_path = tmp / "device_ed25519"
        gen = run(["ssh-keygen", "-t", "ed25519", "-N", "", "-C", "luvia-e2e",
                   "-f", str(key_path)])
        if not check(gen.returncode == 0, "generated device key", gen.stderr):
            return 1
        pub = (tmp / "device_ed25519.pub").read_text().strip()

        if AUTHORIZED_KEYS.exists():
            backup = tmp / "authorized_keys.backup"
            shutil.copy2(AUTHORIZED_KEYS, backup)
            print(f"  note  backed up {AUTHORIZED_KEYS} -> {backup}")

        # Unique per run: a leftover grant from an earlier run would otherwise
        # collide on the device name and abort the whole verification.
        device_name = f"e2e-{os.getpid()}"
        pair = run([str(host_bin), "pair", "--name", device_name, "--role", "controller",
                    "--key", pub, "--json"])
        if not check(pair.returncode == 0, "luvia-host pair --key --json", pair.stderr):
            return 1
        try:
            paired = json.loads(pair.stdout)
        except json.JSONDecodeError:
            check(False, "pair --json emitted JSON", pair.stdout[:500])
            return 1
        device_id = paired.get("id")
        check(bool(device_id), "pair returned a device id")

        code = paired.get("code", "")
        check(code.lower().startswith("luvia1:"), "pair returned a luvia1 pairing code")
        payload = decode_pairing_code(code)
        check(payload.get("v") == 1, "payload version is 1")
        check(bool(payload.get("addrs")), "payload carries at least one address")
        check(bool(payload.get("hk")), "payload carries host key fingerprints")
        check(all(fp.startswith("SHA256:") for fp in payload.get("hk", [])),
              "host key fingerprints use the OpenSSH SHA256 form")
        check(payload.get("user") == user, "payload username matches the login user",
              f"payload={payload.get('user')!r} expected={user!r}")
        check(1 <= int(payload.get("port", 0)) <= 65535, "payload port is in range")
        check(payload.get("role") == "controller", "payload role matches the grant")

        recode = run([str(host_bin), "pair-code", device_id, "--json"])
        if check(recode.returncode == 0, "pair-code reprints for an existing device",
                 recode.stderr):
            again = json.loads(recode.stdout)
            check(decode_pairing_code(again["code"]).get("id") == payload.get("id"),
                  "pair-code payload matches the original grant")

        phase("Happy path over real SSH")
        with Bridge(key_path, user, int(payload["port"])) as bridge:
            ready = open_session(bridge)
            if not check(ready is not None and ready.get("status") == "ready",
                         "bridge prelude accepted the open",
                         f"{ready}  stderr={bridge.stderr()[:400]}"):
                return 1

            bridge.send({"id": "1", "method": "uhp.capabilities", "params": {}})
            caps = bridge.recv()
            ok = caps is not None and "result" in caps
            check(ok, "uhp.capabilities succeeded through the bridge", json.dumps(caps)[:400])
            if ok:
                proto = caps["result"].get("protocol", {})
                check(proto.get("name") == "luvus-uhp" and proto.get("major") == 1,
                      "protocol identity is luvus-uhp/1")

        # session.snapshot is the F1 regression guard: it requires the admin scope, so a
        # bridge that mints only the role's scopes gets `forbidden` here.
        with Bridge(key_path, user, int(payload["port"])) as bridge:
            open_session(bridge)
            bridge.send({"id": "1", "method": "session.snapshot", "params": {}})
            snap = bridge.recv()
            ok = snap is not None and "result" in snap
            check(ok, "session.snapshot succeeded (admin-scope split-token routing works)",
                  json.dumps(snap)[:400])
            snapshot_seq = None
            if ok:
                r = snap["result"]
                check(r.get("type") == "session_snapshot", "snapshot type is session_snapshot")
                check("event_sequence" in r, "snapshot carries event_sequence")
                check(isinstance(r.get("workspaces"), list), "snapshot carries workspaces")
                snapshot_seq = r.get("event_sequence")

        with Bridge(key_path, user, int(payload["port"])) as bridge:
            open_session(bridge)
            bridge.send({"id": "1", "method": "events.subscribe", "params": {}})
            ack = bridge.recv()
            ok = ack is not None and "result" in ack
            check(ok, "events.subscribe acknowledged", json.dumps(ack)[:400])
            if ok:
                check(ack["result"].get("loss_behavior") is not None,
                      "subscribe ack states its loss behaviour")

        # Resuming from the sequence the snapshot just reported must work. Asking
        # for sequence 1 must NOT: a long-lived server has already discarded that
        # history, and Luvus is required to say so rather than silently skipping.
        with Bridge(key_path, user, int(payload["port"])) as bridge:
            open_session(bridge)
            bridge.send({"id": "1", "method": "events.subscribe",
                         "params": {"after_sequence": snapshot_seq}})
            ack = bridge.recv()
            check(ack is not None and "result" in ack,
                  "events.subscribe resumes from the snapshot's event_sequence",
                  json.dumps(ack)[:400])

        with Bridge(key_path, user, int(payload["port"])) as bridge:
            open_session(bridge)
            bridge.send({"id": "1", "method": "events.subscribe",
                         "params": {"after_sequence": 1}})
            ack = bridge.recv()
            err = (ack or {}).get("error", {})
            check(err.get("code") == "resync_required",
                  "a long-expired after_sequence is refused with resync_required",
                  json.dumps(ack)[:400])
            check(isinstance(err.get("sequence"), int),
                  "the resync error reports the server's current sequence",
                  json.dumps(ack)[:400])

        phase("Gating: the bridge must refuse what the role does not grant")
        with Bridge(key_path, user, int(payload["port"])) as bridge:
            open_session(bridge)
            bridge.send({"id": "1", "method": "server.stop", "params": {}})
            denied = bridge.recv()
            check(denied is not None and "error" in denied,
                  "an admin method the role does not grant is denied",
                  json.dumps(denied)[:400])

        with Bridge(key_path, user, int(payload["port"])) as bridge:
            open_session(bridge)
            bridge.send({"id": "1", "method": "no.such.method", "params": {}})
            denied = bridge.recv()
            check(denied is not None and "error" in denied,
                  "an unknown method is denied by default",
                  json.dumps(denied)[:400])

        with Bridge(key_path, user, int(payload["port"])) as bridge:
            open_session(bridge)
            bridge.send({"id": "1", "method": "uhp.capabilities", "params": {},
                         "auth": "luv_tok_forged"})
            denied = bridge.recv()
            check(denied is not None and "error" in denied,
                  "a client-supplied auth token is refused",
                  json.dumps(denied)[:400])


        # The per-frame gating regression guard: the first frame is legitimate, so a
        # bridge that only gates the first frame would forward the second one.
        with Bridge(key_path, user, int(payload["port"])) as bridge:
            open_session(bridge)
            bridge.send({"id": "1", "method": "uhp.capabilities", "params": {}})
            first = bridge.recv()
            check(first is not None and "result" in first, "first frame still succeeds")
            bridge.send({"id": "2", "method": "config.patch", "params": {}})
            second = bridge.recv()
            check(second is not None and "error" in second,
                  "a later frame invoking an admin method is denied (per-frame gating)",
                  json.dumps(second)[:400])

        phase("Result")
        print(f"  {checks - len(failures)}/{checks} checks passed")
        if failures:
            print("  failed:")
            for name in failures:
                print(f"    - {name}")
        return 1 if failures else 0

    finally:
        phase("Cleanup")
        if device_id and host_bin.exists():
            rev = run([str(host_bin), "revoke", device_id])
            print(f"  revoke {device_id}: rc={rev.returncode} {rev.stdout.strip()}")

        # revoke removes the managed line; the backup is a safety net for the
        # case where it did not.
        if backup is not None and device_id and AUTHORIZED_KEYS.exists():
            if device_id in AUTHORIZED_KEYS.read_text():
                shutil.copy2(backup, AUTHORIZED_KEYS)
                print(f"  restored {AUTHORIZED_KEYS} from backup")
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())