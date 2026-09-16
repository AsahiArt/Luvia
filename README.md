# Luvia

A Kotlin Multiplatform client for the [Luvus](https://github.com/RizRiyz/luvus) Universal Harness Protocol (UHP). Native UIs: SwiftUI on iOS, Jetpack Compose on Android.

Luvia does not talk to Luvus directly. The phone opens a pinned SSH session to a machine you control. `luvia-host` is the only command sshd is allowed to run for that key. It mints a short-lived scoped token and proxies NDJSON UHP over a Unix socket to a local Luvus endpoint. The Kotlin client never sends an `auth` field; `luvia-host` refuses client-supplied credentials.

```
phone  --SSH (pinned host keys)-->  luvia-host  --Unix socket-->  Luvus UHP
```

## Prerequisites

- Rust 1.98.0 (see `rust-toolchain.toml`)
- JDK 21 and Android SDK / NDK `29.0.14206865` for the Android app
- Xcode 16+ on macOS for the iOS app (deployment target 17.0)
- A Unix host running Luvus, with `sshd` and a writable `authorized_keys`

Windows is not a `luvia-host` target.

## Install `luvia-host`

On the machine that already runs Luvus:

```sh
curl -fsSL https://raw.githubusercontent.com/AsahiArt/Luvia/main/scripts/install-host.sh | sh
```

Or from a checkout:

```sh
./scripts/install-host.sh          # latest GitHub release
./scripts/install-host.sh v0.1.0   # pinned tag
```

The installer downloads the archive for your OS/arch, verifies the SHA-256 in `SHA256SUMS.txt`, and only then copies `luvia-host` into `$PREFIX/bin` (`/usr/local` if writable, otherwise `~/.local`). It exits non-zero if the checksum is missing or does not match. It will not install an unverified binary.

To build from source instead:

```sh
cargo build --release -p luvia-host
```

## Pairing

1. Open Luvia on the phone and start pairing. The app generates a device SSH key and shows a single command, for example:

   ```
   luvia-host pair --name Android --role controller --key 'ssh-ed25519 AAAA…'
   ```

2. Run that command on the host. `luvia-host` writes a grant, installs a `restrict,command="luvia-host bridge --device <id>"` line in `authorized_keys`, and prints a `luvia1:…` pairing code plus a QR.

3. Scan the QR (or paste the code) on the phone. The payload pins every SSH host-key fingerprint (`hk`), lists reachable addresses in preference order (`addrs`), and includes `dk` so the phone can confirm the code belongs to the key it just generated.

**Tailscale.** If the `tailscale` CLI is installed and the node is running, `luvia-host pair` places the node's tailnet IPs (`100.x`, `fd7a:115c:a1e0::`) and MagicDNS name ahead of LAN addresses in `addrs`, so the phone reaches the host from any network without port forwarding. The phone still pins the SSH host key; Tailscale only changes routing. Set `LUVIA_NO_TAILSCALE=1` to skip detection, or pass `--address` to override the list entirely. The host row shows a Tailnet badge when the active address is a tailnet address.

Roles: `observer` (read) or `controller` (read plus workspace / agent / terminal / orchestration). Revoke with `luvia-host revoke <id>`.

## Herdr

`luvia-host` can front a running [Herdr](https://herdr.dev) server in addition to Luvus. Discovery lists a `herdr` session (and `herdr-<name>` for other Herdr sessions) next to Luvus. Opening one skips Luvus token minting, shells out to the installed `herdr` CLI, and answers the UHP subset the phone already renders — agents, panes, workspaces, snapshot, and live status — without exposing Herdr's socket. Methods Herdr has no equivalent for are omitted from `uhp.capabilities`. See [`docs/herdr-backend.md`](docs/herdr-backend.md).

## Build

From the repository root. Prefer `make`; CI runs the same targets. `make android` / `make ios` / `make mobile` install and launch on **every connected physical phone** (USB or wireless), and fall back to emulator / Simulator if none are present.

```sh
make help
make mobile           # iOS then Android on every connected phone
make android          # every physical phone, else AVD
make ios              # every USB/wireless iPhone, else Simulator (macOS)
make ios-device       # fail if no paired iPhone
make host             # release luvia-host
make test             # Rust workspace + Kotlin JVM tests
```

**Shared Kotlin + Rust transport**

```sh
make shared-test      # ./gradlew :shared:jvmTest
```

**Android**

```sh
make android-build    # ./gradlew :androidApp:assembleDebug
make test-android     # debug assemble + unit tests (CI)
make android-release  # R8
```

Release (`assembleRelease`) enables R8. Signing is optional: set `LUVIA_STORE_FILE`, `LUVIA_STORE_PASSWORD`, `LUVIA_KEY_ALIAS`, and `LUVIA_KEY_PASSWORD`, or a gitignored `keystore.properties` with the same keys. An unconfigured checkout still builds `debug`.

Pin a device with `ANDROID_SERIAL=…`.

**iOS**

```sh
make ios-compile      # commonMain metadata + iosArm64
make ios-framework    # linkDebugFrameworkIosSimulatorArm64
make ios-build        # unsigned Simulator app (CI)
make ios-open         # open iosApp/iosApp.xcodeproj
```

Xcode runs `:shared:embedAndSignAppleFrameworkForXcode` in a Run Script phase. Physical-device runs use Automatic signing (`DEVELOPMENT_TEAM` in the Xcode project). Override with `TEAM_ID` in `iosApp/Configuration/Config.xcconfig` if needed. Pin a phone or simulator with `IOS_UDID=…`.

**Host**

```sh
make host-test        # cargo test -p luvia-host
make host             # cargo build --release -p luvia-host
```

## ABI and platform support

Android ships **64-bit only**:

| ABI        | Why |
|------------|-----|
| `arm64-v8a` | Physical devices |
| `x86_64`    | Standard Android emulator (without this, contributors cannot run the app) |

`armeabi-v7a` and `x86` are omitted. 32-bit JNI/UniFFI slices would double native size for hardware that is not a support target.

iOS: `iosArm64` (device) and `iosSimulatorArm64` (Apple Silicon simulator). There is no `iosX64` target.

`luvia-host` releases: macOS universal (`arm64` + `x86_64`) and Linux (`x86_64`, `aarch64`).

## Compose Material3 version

`org.jetbrains.compose.material3` is pinned at **1.11.0-alpha07** in `gradle/libs.versions.toml`. That is the Material3 artifact published alongside Compose Multiplatform 1.11.1. The latest *stable* Material3 on Maven Central is 1.9.0, which is a different generation than Compose 1.11.x. The app only uses ordinary Material3 widgets (`Scaffold`, `PrimaryTabRow`, `CenterAlignedTopAppBar`, buttons, fields); those exist on 1.9.0, but dropping to 1.9.0 would desynchronize the Compose catalog. The alpha is therefore a deliberate alignment choice, not an accident.

## Notification surfaces

Luvia has no push server. There is no APNs/FCM path, no relay, and no credential beyond the device SSH key — the phone speaks only to your host. Both notification surfaces are therefore driven by the running app and update only while it has execution time.

| Surface | Shows | Alerts |
|---------|-------|--------|
| iOS Live Activity (Lock Screen, Dynamic Island) | Link state and agent counts | Banner and sound when the blocked-agent count rises |
| Android ongoing notification | Same counts plus link freshness | None; the channel is `IMPORTANCE_LOW` and the notification is `setOnlyAlertOnce` |

Treat both as a **status glance, not a pager**. For Beta that means:

- iOS publishes a 30-second `staleDate`. Once the app is suspended and events stop arriving, the activity falls back to its stale presentation and stays there. No further alert is delivered until you foreground the app.
- The Android notification is posted from the composition and dismissed when it leaves. If the process is killed, the notification goes with it.
- Nothing wakes the phone for an agent prompt that arrives while the app is fully suspended.

Each surface is confined to one class per platform (`LiveActivityController`, `StatusNotificationController`), so a push path can be added later without touching the shared client.

## ACP agents

Besides Luvus panes, Luvia can launch any [Agent Client Protocol](https://agentclientprotocol.com) agent (Codex, Claude Code, Gemini CLI, …) directly on the host. `luvia-host bridge` acts as the ACP client: it spawns the agent as a child process, speaks JSON-RPC to it over stdio, and exposes the session to the phone as the `luvia.acp.*` UHP methods — streamed messages, tool calls, plan, and permission prompts you answer from the phone. Agents never see the SSH or Luvus credentials. Built-in agents are auto-detected on `PATH`; add or override entries in `~/.config/luvia/host/acp-agents.json` (`[{"id","name","command","args"}]`). Controller role required. The wire contract is in [`docs/acp-contract.md`](docs/acp-contract.md).

## Push wakes

SSH dies when the phone sleeps, so `luvia-host watch` (and the bridge, for ACP permission prompts) POSTs a content-free wake through a relay to APNs / UnifiedPush / FCM. The relay never sees terminal text — only a token, a wake kind, a count, and an 8-hex host hash.

Point the host at a relay with `~/.config/luvia/host/relay.json`: `{ "url": "https://relay.example.com", "key": "<bearer>" }`. Then run `luvia-host watch` next to Luvus (it polls Herdr if Luvus is down).

launchd: `ProgramArguments = ["/usr/local/bin/luvia-host", "watch"]`, `KeepAlive = true`. systemd --user: `ExecStart=/usr/local/bin/luvia-host watch` and `Restart=always`.

Self-host `luvia-relay` (same binary as hosted) with `LUVIA_RELAY_KEYS` and optional APNs/FCM env; the contract is [`docs/push-relay.md`](docs/push-relay.md).


## Security model

**Trusted**

- The host you SSH into, `luvia-host` on that host, and the local Luvus process behind the Unix socket.
- The phone OS keystore/keychain that wraps the device SSH private key.

**Not trusted**

- The network path. Host keys are pinned; a connection is accepted only if the presented key fingerprint is in the pairing-code `hk` set (any match). An empty pin set is a configuration error, never “accept anything”.
- The Kotlin client for authorization. `luvia-host` mints delegated tokens and **refuses client-supplied `auth`**.

**Pinned**

- SSH host public keys (`SHA256:` + unpadded standard base64 of the SHA-256 of the key blob, OpenSSH form).
- The device public key fingerprint in `dk`, so a scanned code cannot be swapped onto a different phone key.

**Where private keys live**

- Device SSH private key (OpenSSH PEM): wrapped at rest.
  - Android: AES-GCM key in AndroidKeyStore (non-exportable) wrapping the PEM; ciphertext + IV in SharedPreferences.
  - iOS: Keychain generic password, `AfterFirstUnlockThisDeviceOnly`, not synchronizable.
  - JVM/tests: file with mode `0600`.
- The PEM itself cannot be a non-exportable Keystore key because `russh` must consume the raw key.
- Host SSH host keys stay on the host; the phone stores only fingerprints.
- Grant files and `authorized_keys` live on the host under `luvia-host`’s paths.

## Layout

| Path | Role |
|------|------|
| `host/` | `luvia-host` (Rust) |
| `transport/` | Pinned SSH via `russh`, UniFFI to Kotlin |
| `shared/` | KMP client (JVM, Android, iOS) |
| `androidApp/` | Compose app |
| `iosApp/` | SwiftUI app |

## License

Apache-2.0. See `LICENSE` and `NOTICE`.
