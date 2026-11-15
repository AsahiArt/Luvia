# Machines page resolution

Pre-edit matrix: `page-frame-source-gap-test-matrix.md`. This file is the after-edit record.

## What changed

Only `iosApp/iosApp/MachinesHome.swift`, plus this handoff directory, then an ordinary merge of PR60 `ca46e3fdeeb8e4f9aa64e461fd256594d710b945`. No rebase and no force. Session list files were not edited.

`HostFilterBridge.swift`, `HostSessionFilter.kt`, and `HostSessionFilterTest.kt` were not modified. Scheme ② behavior on that base already matches the tests and `ContentView.focusFilter`.

| Gap | Resolution |
|---|---|
| G1 | Top-right + is omitted when `showsEmpty`. |
| G2 | Menu item is 扫码配对. |
| G3 | Current host name is `.title3`. Other hosts use `.headline`. |
| G4 | Host mark is `laptopcomputer`. |
| G5 | Path rows end with a chevron. |
| G6 | Live host with no paths shows 没有打开的路径 and 新建会话. The callback defaults to a no-op. See dependency-requests.md. |
| G7 | Main card has a context menu and no swipe. Other online hosts swipe 断开 in gray. Offline or revoked hosts swipe 移除, then `confirmationDialog`. Path rows expose 在此新建会话 as a context menu, an accessibility action, and `swipeActions`. |
| G8 | Marks are `sortSessions` order before the 3-mark cap. |
| G9 | Live current subtitle is 已连接, then the PR59 route word when `route != nil`, then `N ms` only when `latencyMs != nil`. Other live hosts show the route word, or 已连接 when the route is nil. The dialed address is not the connection method. Detail shows 线路 only when `route != nil`, and still shows 地址 as the address. |
| G10 | `pairingRevoked` uses `failureMessage` or `PROVE_REJECTED_ZH`. The current card’s button is 重新配对 and does not call `model.connect`. The status dot stays gray. No route is shown. |
| G11 | Compile root cause, not a frame gap. `11d` line 587 passed `host.username`. The account name is `HostViewState.user`. The call now passes `host.user`. The helper parameter stays `username`. |
| G12 | VoiceOver labels follow the README patterns, including 主目录 for `~`. Accessibility sizes wrap the path onto two lines. The current host name stays `.primary` when offline. |
| G13 | Other-host order is ranked once on appear: live and connecting by `lastUpdated`, then offline and stale. Later arrivals append. Leaving the tab destroys the view, so the next entry ranks again. |

Not changed, on purpose:

- Empty command stays `luvia-host pair`.
- Offline visible copy stays 「离线 · 上次 X 前」. The frames omit 上次 / say 断开. The README sentence wins.
- Light 等你 color stays `#C2410C`. Dark stays `#FFB340`.
- B, C, mux D, d474 after, and merged-filter proposals were not implemented.
- Android `MachinesBody` was not rewritten.

## Resulting commits

| Commit | Tree | Role |
|---|---|---|
| `b89c4e00e983c5f9dce58bb631d61d93f04f8ea7` | machines fix on `11d42366` | `MachinesHome` plus this handoff |
| `c396fb979e29cb2ed63c85e12351c42ba070137d` | `1468b9b7871f1105180b9e78e3630f9af325ad34` | Ordinary merge. Parents `b89c4e00e983c5f9dce58bb631d61d93f04f8ea7` and `ca46e3fdeeb8e4f9aa64e461fd256594d710b945`. |

The merge changed `ChatFirstApp.kt` and `ContentView.swift` only. It did not change `MachinesHome.swift`. `host.username` is gone from those three call sites. `pair_help` and `pair_role_note` are not in the merged tree.

## Tests and logs

| Check | Log |
|---|---|
| Ancestry | `git merge-base --is-ancestor` succeeded for `33a5adbb`, `837191ee`, `2eb6e0f1`, `fe3a4da`, and `a1375f93`. `HEAD` at audit time was `11d423663f8ad1937c9386ef6ceb1c611b66dac6`, tree `f9a147a6fdd1fc5881802b1db73b41ab2e34c2e2`. Worktree was clean. |
| PNG hashes | Extracted `png-checked-in/lv-ios-mach-{light,dark,offline,empty}.png` sha256 values match frame-index. |
| SDK | `ANDROID_HOME` unset, `ANDROID_SDK_ROOT` unset, no `local.properties`, no `sdkmanager`. Gradle was not started. SDK was not installed. |
| iOS toolchain | `xcodebuild` and `swiftc` are absent. MachinesHome was not compiled. Do not treat this branch as CI-green. |
| `make check` | Not run. The target is Rust fmt and clippy and does not compile this Swift file. |
| `HostSessionFilterTest` | Not run, and not edited. Expectations were not changed. |

## Not evidence

No phone, simulator, or paired host was available. Nothing here is a light-mode, dark-mode, empty, offline, revoked, or no-route screenshot of the app. Source inspection and the approved PNGs are the only comparison.

Inner path `swipeActions` are attached on the path button. The list row is the whole card, and SwiftUI presents swipe actions on that row. The host swipe is the one that can show. The path action is still on the context menu and VoiceOver. That presentation was not executed.

`SessionListView` still does not pass `onCreateSession` or a measured `latencyMs`. That file was not edited. PR60 `ca46e3f` already changed `ContentView` and `ChatFirstApp` to `user`. This branch merges that commit. It does not rewrite those files. No Swift compiler is on this VM, so the `host.user` fix is source-checked only and is not a green iOS app compile.
