# Machines page dependency requests

Base `11d423663f8ad1937c9386ef6ceb1c611b66dac6`. Machines owner did not edit these files. No second filter model, route enum, or machines page was added.

## bc-52323650 · list owner

### iOS `SessionListView.swift`

`MachinesHome` now takes:

```swift
var onCreateSession: (HostViewState, String?) -> Void = { _, _ in }
```

`SessionListView.machines` does not pass it, so 新建会话 and 在此新建会话 do nothing. Wire that closure only. Do not edit `MachinesHome`.

Requested behavior, using the existing `onFocusFilter` and `onNewSession`:

1. `cwd == nil` (live host, no open path): `onFocusFilter(host, SessionScope.CurrentHost, .all)`, then `onNewSession()`.
2. `cwd` set (path row): `onFocusFilter(host, SessionScope.Path(cwd), .all)`, then `onNewSession()`.
3. `onFocusFilter` already selects the host, writes scheme ② memory, and flashes the session filters for 0.6s. Do not also call `applyHostSwitch` on this path. That reset is only for a plain host switch.
4. After the session exists, open it with the existing open-thread path. One session still lands on the list first only when the user tapped a path row (`onFocus` already does that). 新建会话 is the create path above, not a second list filter.

`latencyMs` is still the single optional `SessionListView` argument, default `nil`, and `ContentView` does not pass it. `HostViewState` has no RTT. Do not invent milliseconds. Pass a value only when a real per-host measurement exists on the model.

### Android `SessionListScreen.kt` `MachinesBody`

Read-only audit. There is no approved Android machines PNG. Do not invent a new page. Replace the placeholder in this file only, using the approved machine-A behavior and existing tokens.

What is there now (`MachinesBody`, about line 929):

- Title copy `R.string.machines_placeholder` (“已配对的电脑。点一台查看它的会话。”).
- Empty copy `R.string.empty_pair_first`.
- One row per host: `ConnectionDot`, name, `connectionCopy()`, and `RouteChip(host.route)` when `route != nil`.
- A check mark on the selected host. The row calls `onSelectHost` and `SessionListScreen` immediately sets the tab back to Sessions.
- Text buttons 配对电脑 and 打开终端. No counts, paths, offline opacity, reconnect, empty illustration, or revoked-versus-reconnect split.

Keep these facts:

- `HostUiModel.route` is null unless the link is online. `toUi()` sets `pairingRevoked` from `Failure.isPairingRevoked()` and `errorMessage` from `connectMessage()`. AuthRejected and ProveRejected have no route (`ConnectionRouteTest`).
- `RouteChip` labels stay 局域网 / Tailnet / 未知. Do not label a row Tailscale or SSH, and do not show `address` as the connection method.
- Revoked copy stays `connectionCopy()` (`PROVE_REJECTED`, localized). Offer pair again. Do not call `onRetry` / connect for `pairingRevoked`.
- Session counts and paths already exist: `filterSessionsByHost`, `openSessionPaths`, `homeRelativePath`, `HostSessionFilter`. Scope for a path tap is `SessionScope.Path`, status `SessionFilter.All`. Count taps use `SessionScope.CurrentHost` and NeedsYou / Running / All. That write goes through the existing `onFilter`. Do not add a parallel filter type.
- Offline and stale hosts do not list paths. Live host with an empty `openSessionPaths` result shows the no-path row. Other offline hosts are 55% opacity. Current host stays full opacity and stays first.
- Strings live in `strings.xml` (strings owner, below). Reference new ids. Do not hardcode a second copy of `PROVE_REJECTED_ZH`.

No change to `HostBanners.kt` or `RouteChip.kt` is required for this.

## bc-fdcabbd6 · router

No account-name change is requested. PR60 `ca46e3f` already passes `selected?.user` in `ChatFirstApp` and `host.user` in `ContentView`, and leaves `HostUiModel.user` / `HostViewState.user` as the account name. `MachinesHome` now passes `host.user` in the owned file. Do not rename `user` or the `username` parameter.

Later terminal-router and `TerminalPageView` edits stay with this owner. Machines files stay with the machines branch. No latency field exists on the runtime. Do not add one for the machines subtitle.

## Strings owner

Android machines copy is not in `strings.xml` except `machines_placeholder`, `empty_pair_first`, `pair_computer`, and `tab_machines`. iOS machines strings stay in `MachinesHome` the same way the session list hardcodes its Chinese. Requested Android ids, for the list owner to reference:

| Id | Text |
|---|---|
| `machines_empty_title` | 还没有配对的电脑 |
| `machines_empty_command` | luvia-host pair |
| `machines_pair_new` | 配对新电脑 |
| `machines_other` | 其他电脑 |
| `machines_reconnect` | 重新连接 |
| `machines_pair_again` | 重新配对 |
| `machines_no_paths` | 没有打开的路径 |
| `machines_new_session` | 新建会话 |
| `machines_new_session_here` | 在此新建会话 |
| `machines_waiting` | 等你 |
| `machines_running` | 运行中 |
| `machines_sessions` | 会话 |
| `machines_offline` | 离线 · 上次 %1$s前 |

Empty command stays `luvia-host pair`. The machines frame still says `luvus-companion pair`. That string does not match the host CLI. Do not add it.

Route strings already exist: `route_lan`, `route_tailnet`, `route_unknown`. Reuse them. Do not add Tailscale or SSH labels.
