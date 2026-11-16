# Machines page · frame → source → gap → test

Written before any Machines page source edit.

| Item | Value |
|---|---|
| Base commit | `11d423663f8ad1937c9386ef6ceb1c611b66dac6` |
| Base tree | `f9a147a6fdd1fc5881802b1db73b41ab2e34c2e2` |
| Parents | `3b650324cecc344cb73510f622215da66541488e` `33a5adbb5b78321f9d81bcd78f3d78e3b41a18c0` |
| Ancestry | PR53 `33a5adbb`, PR52 `837191ee`, PR58 `2eb6e0f1`, PR59 `fe3a4da`, main `a1375f93` are ancestors. Worktree was clean. Branch `cursor/machines-page-a-6ab5` is new. Existing PR branches were not moved. |
| Approved direction | Machine A + filter scheme ②. `docs/ios-machines-README.md` header. B, C, mux D, d474 after, and the unselected merged-filter proposals are out of scope. |
| Frames | `lv-ios-mach-light` `lv-ios-mach-dark` `lv-ios-mach-offline` `lv-ios-mach-empty`. Zip paths `png-checked-in/lv-ios-mach-*.png`. `/workspace/designer/...` paths in READY/frame-index are designer-local, not repo paths. |
| Checked-in sha256 | light `253081d20e57e4d94281aaf9075a50f02b10156e9180e7147edec97dab5c5129`; dark `890d2030a8a8732ee3e5b5ee5086f4be11f140cde886c60b701c6b3d1262197c`; offline `bb948fda8695862f4c51f2be2e71e8b6a1b81bee70fed43d97b89a507a643deb`; empty `e4117a72ea4b385ddfe4e6366745c8681ab75981282c40da81b47c6420502eb7`. Extracted zip bytes match these. |
| Owned files | `iosApp/iosApp/MachinesHome.swift`, `iosApp/iosApp/HostFilterBridge.swift`, `shared/src/commonMain/kotlin/tech/asahiart/luvia/thread/HostSessionFilter.kt`, `shared/src/commonTest/kotlin/tech/asahiart/luvia/thread/HostSessionFilterTest.kt`, plus exclusive machines helpers/tests. |
| Not owned | `SessionListScreen.kt`, `SessionListView.swift`, `HostBanners`, `RouteChip` (list owner bc-52323650). `ContentView.swift` and `UIModels.swift` (router / UIModels). Theme, Glass, strings, protocol, design. |

Text wins when a frame and `docs/ios-machines-README.md` disagree. PR59 route stays the classified `HostRoute` (`Tailnet` / `局域网` / `未知`), null when the link is not online. PR57 revoked pairing stays `pairingRevoked` plus `PROVE_REJECTED_ZH`, with no invented route.

## Already matching (no edit)

| Frame / rule | Source | Test |
|---|---|---|
| Scheme ② memory, host switch resets scope and keeps status, path pin, cwd normalize | `HostSessionFilter.kt`, `HostFilterBridge.swift`. Path and count taps call `ContentView.focusFilter`, which `remember`s the chosen scope and status and flashes 0.6s unless Reduce Motion. Host switch uses `applyHostSwitch`. | `HostSessionFilterTest` already covers scope, status intersection, switch, pin, encode, home-relative paths. Not re-run here: Android SDK is absent (`ANDROID_HOME` unset, no `local.properties`, no `sdkmanager`). |
| Large title 机器, glass +, counts 等你 / 运行中 / 会话, path `~/` via `hostHomeDirectory`, max 3 marks and `+N`, waiting capsule hidden at 0, current host card on top, other offline hosts at 55% and without paths, reconnect progress and in-card red failure, 0.35s spring | `MachinesHome.swift` | No iOS test target. No `swiftc` / Xcode on this VM. |
| Offline visible copy 「离线 · 上次 X 前」 | `offlineLine` | Frame says 「离线 · 2 天前」 and 「离线 · 3 分钟前断开」. Text wins. Left as the README sentence. |
| Empty command | `MachinesHome` prints `luvia-host pair` | README and empty PNG say `luvus-companion pair`. Shipped CLI, `HostManagerTest`, and the session empty line use `luvia-host pair`. Not changed. |
| Dark 等你 `#FFB340` and 20% capsule fill | `needsYouColor` | Light hex `#C2410C` is not in the README. Left unchanged. No device light/dark capture. |

## Proven gaps in owned iOS source

| Id | Evidence | File | Fix in this branch |
|---|---|---|---|
| G1 | Empty README and `lv-ios-mach-empty`: hide the top-right +. Toolbar always inserts the Menu. | `MachinesHome.swift` | Hide the toolbar item when `showsEmpty`. |
| G2 | README menu labels are 扫码配对 and 输入配对码. Source says 扫码. | `MachinesHome.swift` | Rename the first menu item. |
| G3 | README: main-card name `.title3`, other hosts `.headline`. One style is used for both. | `MachinesHome.swift` | Split the fonts. |
| G4 | Light/dark/offline frames use a laptop mark. Host cards use `desktopcomputer`. | `MachinesHome.swift` | Use `laptopcomputer`. |
| G5 | README and frames: path row ends with ›. `pathRow` has no chevron. | `MachinesHome.swift` | Add the chevron. |
| G6 | README and offline frame: a live host with no paths shows 没有打开的路径 and 新建会话. Live cards render nothing. | `MachinesHome.swift` | Add the row. Creation and tab switch stay a callback. `SessionListView` is not edited, so the callback default is a no-op until the list owner wires it. |
| G7 | README swipe rules. Source swipes every card, including the main card, with 重新连接, orange 断开, and 移除 without confirmation. | `MachinesHome.swift` | Main card: context menu 断开 / 移除, no swipe. Other host: 断开 (gray) when online, 移除 with `confirmationDialog` when offline. Path: 在此新建会话 via swipe, context menu, and VoiceOver. Nested path swipe is limited by List, see the dependency note. |
| G8 | Mark order is 等你 → 运行 → 空闲. `catalog` passed in is `realCatalog`, not `sortSessions`. `prefix(3)` keeps arrival order. | `MachinesHome.swift` | Sort with existing `SessionListKt.sortSessions` before the prefix. No new filter API. |
| G9 | Live subtitle prints `host.address` (dialed IP or lastGood). PR59's user-facing route is `HostRoute`, and it is nil off a live link. Frames show a connection method, not an IP. There is no per-host RTT on `HostViewState`. | `MachinesHome.swift` | Current live line: 已连接 · route label · latency only when `latencyMs` is non-nil. Other live line: route label, or 已连接 when route is nil. Never invent Tailscale, SSH, or milliseconds. |
| G10 | Revoked hosts still get 重新连接, which calls `model.connect`. Route is nil for AuthRejected and ProveRejected. Copy is already `FailureText.connectActionable` / `PROVE_REJECTED_ZH`. | `MachinesHome.swift` | Revoked subtitle is that copy. Current card offers 重新配对 (`onPair`) and does not call `connect`. Detail shows 线路 only when `route != nil`. |
| G11 | Moved. This is a compile failure, not a frame gap. See the compile section. | `MachinesHome.swift` | Pass `host.user`. Do not rename `user` or the helper parameter `username`. |
| G12 | VoiceOver section and AX1 path wrapping are specified and not implemented. Current-host name goes `.secondary` when offline; the offline frame keeps the name at full weight and only quiets the icon and dot. | `MachinesHome.swift` | Labels, ~ spoken as 主目录, mark `accessibilityValue`, AX1 two-line paths, primary name on the current card. |
| G13 | README: other hosts stay in recent-use order, offline last, and the visible order does not jump. `otherHosts` follows `model.hosts` order. | `MachinesHome.swift` | Rank on appear (live/connecting by `lastUpdated`, then offline/stale). Keep that order while the page is open. Append hosts that arrive later. Recapture on the next appear. |

`HostFilterBridge.swift`, `HostSessionFilter.kt`, and `HostSessionFilterTest.kt` have no proven scheme-② defect. They are not edited. The helper parameter stays `username`. That name is the function argument, not a model field.

## Compile root cause (not a visual spec)

`11d423663f8ad1937c9386ef6ceb1c611b66dac6` `MachinesHome.swift` line 587:

```swift
HostFilterBridge.homeDirectory(username: host.username, paths: paths)
```

`HostViewState` has no `username`. The account name is `HostViewState.user` (`profile.user`). Android’s matching field is `HostUiModel.user`. `hostHomeDirectory` and `HostFilterBridge.homeDirectory` keep the parameter name `username`. This branch does not rename the model or that parameter.

PR60 `ca46e3fdeeb8e4f9aa64e461fd256594d710b945` (tree `557333440edead1316abb9ac150099b23929060c`, parent `1c429da2a9616caabfc76c7029330de853adaf16`) already changed `ChatFirstApp` `selected?.username` to `selected?.user` and `ContentView` `host.username` to `host.user`, and removed unused `pair_help` and `pair_role_note`. That commit does not touch `MachinesHome.swift`. After it, line 587 was still `host.username`, so the iOS app still does not compile.

The owned fix is the call argument: `host.user`. No other `host.username` remains in the owned files. This VM has no `swiftc` or `xcodebuild`, so this is a source finding, not a compile log.

## Android (read-only)

No approved Android machines PNG. `SessionListScreen.kt` `MachinesBody` (about line 929) is still the placeholder `machines_placeholder`, a host row with `connectionCopy()` and `RouteChip`, and a pair button. Tapping a row selects the host and leaves the machines tab. It does not implement counts, paths, empty, offline 55%, no-path, or revoked-versus-reconnect. Route nullability on `HostUiModel.route` is already truthful. This file is not rewritten. The change request is `dependency-requests.md`.

## Tests and evidence this VM cannot produce

| Check | Result |
|---|---|
| Android SDK | Absent. `ANDROID_HOME` and `ANDROID_SDK_ROOT` unset. No `local.properties`. No `sdkmanager`. Gradle was not run. SDK was not installed. |
| iOS compile | No `xcodebuild` or `swiftc`. Full app compile stays a CI gate. |
| `make check` | Rust fmt/clippy only. It does not compile this page. Not run. |
| Device / host session | None on this Linux VM. Fixture and source reads are not light/dark or live-session evidence. |
| `HostSessionFilterTest` | Existing expectations were not loosened. The class was not changed, so it was not re-run. |
