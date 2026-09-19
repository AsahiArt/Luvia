# Luvia mobile redesign

Status: accepted for implementation (2026-09-19)

Phone IA follows **objects the user cares about**, not UHP method families. This document supersedes the **tab bar** in `docs/surface-model.md`. It does **not** change the object tree, locators, or ADR 0001 (`task.next` / `agent.send` never; `workspace.focus` only as Review locator; terminal observe only while Terminal is visible).

Brand remains Warm Minimal (`docs/brand-spec.md`).

---

## 1. Principles

1. **Glanceable status first.** Opening the app answers: which Hosts are live, who is Blocked, can I talk to them.
2. **Answer Blocked Agents in two taps.** Host with a badge → Agent row → sticky answer card. No Connect tap, no Transcript/Terminal chip, no confirm dialog in the way.
3. **Review is next.** Project-scoped Diff and Tasks live together as a Workspace. Everything else (Files, Search, Worktrees, Layout, Automations) is a tool, not a peer of conversation.
4. **One mental model:** `Hosts → Agents (pane or ACP) → conversation`. ACP is not a side launch path. A pane Agent and an ACP session are the same kind of thing: a conversation with a type badge.
5. **Hide TUI geometry.** No Host tab named Layout. Pane IDs, workspace indices, and `workspace.focus` are locators, not chrome.
6. **Cached truth while the link moves.** Selecting a Host auto-connects. Connecting / stale / offline still render the last snapshot, with a status pill. Empty "Connect to this host" is only for a Host that has never connected.
7. **Sans for UI, mono for code.** Transcript, Terminal, Diff, fingerprints, cwd, pairing command: mono. Everything else: system sans. Serif only for empty-host / pairing display titles (brand).
8. **Platform-native chrome, shared structure.** Same screens, same actions, same copy. Android = Material 3 (dynamic color). iOS = system materials + SF Symbols. Placement of sheets / toolbars may differ; **which screen owns which action must not.**

Peak moment: answering a Blocked Agent (large Yes/No, haptic, card dismisses). End moment: returning to the Agent list with the badge gone.

---

## 2. Information architecture

### Decision: no cross-host Inbox tab

The Host list **is** the inbox.

- A Host is the trust and connection boundary (Role, snapshot, stream budget). Mixing Blocked Agents from two Hosts into one list hides that.
- Host rows already carry Live/Stale/Offline + a Blocked count. Sort Hosts with `attentionCount > 0` first, then Live, then the rest.
- Tapping a Host with exactly one Blocked Agent (and no ACP permission) may auto-open that conversation; otherwise land on Agents with Blocked pinned at top.

Do not add a global "Home/Inbox" tab.

### Object tree (unchanged)

```
Host
  └── Workspace (project)
        └── Agent conversation
              ├── Pane Agent (Luvus) + optional Terminal
              └── ACP session (phone-launched; not a pane)
```

### Phone navigation graph

```
Hosts
  ├─ FAB → Pair (stack/sheet)
  └─ tap Host → auto-connect → Host
        ├─ tab Agents
        │     ├─ FAB New agent → ACP launch sheet
        │     ├─ Agent row → Conversation (pane)
        │     ├─ ACP row → Conversation (ACP)
        │     └─ Resumable session → resume (stays on list until a pane appears)
        ├─ tab Workspace
        │     ├─ Project picker (required when >1 project)
        │     ├─ segment Review → file list → Diff file (notes)
        │     └─ segment Tasks → board + add
        └─ tab More
              ├─ Files / Search / Worktrees / Automations / Layout
              └─ Host settings (push, edit connection, unpair, Role)
```

**Tab bar (exactly three):** Agents · Workspace · More.

| Tab | Owns | Not |
|---|---|---|
| Agents | Unified conversation list, New agent, Mission glance | Terminal as a Host tab |
| Workspace | Review + Tasks for the selected project | Host-wide unfiltered dumps |
| More | Files, Search, Worktrees, Automations, Layout, Host settings | Primary daily path |

`HostSection` in shared Kotlin stays (capability + load). UI no longer maps 1:1 onto it. More rows appear only when `visibleSections()` includes that section.

### Tablet / split (≥600dp Android, `NavigationSplitView` iOS)

- Leading: Host list (always).
- Trailing: Host chrome (same three tabs).
- Conversation pushes on the trailing column (iOS `navigationDestination`; Android extra `NavKey`).
- Pairing occupies the trailing column on phone; sheet on tablet/iOS (keep current iOS sheet).

### Auto-connect

On Host open (list tap / split selection / post-pair):

1. If `HostLink` is not `Online` and not already `Connecting`, call `HostManager.connect(id)`.
2. Host chrome renders immediately from cached snapshot (`hasSnapshot` / last `HostUhpState`).
3. Top bar shows `StatusPill` (Live / Connecting / Stale / Offline), not a Connect button.
4. Disconnect lives in Host settings (and iOS leading swipe, as today). Cancel Connecting = disconnect.
5. Mutations stay disabled until `connected && !isObserver`.

No new shared "auto-connect flag". Both platforms call `connect` from the same navigation event.

### Where things live (was inconsistent)

| Action | Home |
|---|---|
| Pair | Hosts FAB / empty-state CTA |
| Connect | Implicit on Host open |
| Disconnect / Cancel | Host settings; iOS host-row swipe |
| Edit connection, Unpair, Push, Role | Host settings sheet from Host toolbar gear |
| New agent (ACP) | Agents FAB; empty-state card if list empty and `acpSession` |
| Resume / Fork / Name | Conversation overflow (Name, Fork); Resume on list section |
| Review / Tasks | Workspace tab; Agent project name jumps here with workspace already set |
| Files, Search, Worktrees, Layout | More |
| Automations | More (host ledger, not a peer tab) |
| Terminal observe/control | Conversation → Terminal segment only |

Android overflow-menu vs iOS sheet for More is replaced by a **More tab** on both.

ACP is **not** `fullScreenCover` vs inline: both platforms push the same Conversation screen. Launch remains a sheet.

---

## 3. Screen-by-screen spec

Shared states for every list/detail (implement as `EmptyState` / banners, never a blank Scaffold):

| State | UI |
|---|---|
| Loading (no cache) | Centered spinner + title |
| Loading (has cache) | Keep list; pull-to-refresh indicator |
| Empty | `EmptyState` title, one-sentence why, optional CTA |
| Error | Sticky error banner; keep cache; retry = pull or banner button |
| Unconfirmed | `UnconfirmedBanner` (existing copy); Check, never resend |
| Stale | `StatusPill(Stale)` on Host; content remains |
| Offline | `StatusPill(Offline)`; Observer/Controller chrome still visible; mutations off |
| Connecting | `StatusPill(Connecting)` + cache; no empty "Connect" |

Copy uses CONTEXT.md words: Host, Agent, Blocked, Transcript, Agent prompt, Agent keys, Diff, Review note, Task, Pane.

### 3.1 Hosts

**Layout.** Large title "Luvia". `LazyColumn` / inset grouped list of Host rows. Tonal FAB "Add host". Pull to refresh all.

**Host row (48dp+ hit target):**

```
[Avatar + link-dot]  Name                         [Blocked N]
                     Live · 12s · address  Tailnet? Herdr?
                     error (if any), 2 lines
```

- Avatar: first letter on `primaryContainer`; 11pt link-dot (`linkLive` / `linkConnecting` / `linkStale` / `linkOffline`) bottom-trailing.
- Blocked badge: accent capsule, only if `blockedAgents > 0` (plus ACP permission — see `attentionCount`).
- No inline Connect/Disconnect button on Android (that was the awkward extra tap). iOS keeps leading swipe Disconnect, trailing Unpair.

**States.** Empty: serif title "No Hosts", three numbered steps (install / pair / scan), one glass/tonal CTA. Error on a row: error color, still tappable (opens Host + retries connect).

**Primary action.** Tap = open Host + auto-connect. FAB = Pair.

**Gestures.** Pull-to-refresh. iOS swipe as today. Long-press unused.

### 3.2 Pair

Keep the existing stepped flow (identity → command → scan/paste → connecting). Visual: serif title, one primary action per step, mono for the install command and fingerprint. After success, pop to the new Host (auto-connect already happens in `completePairing`).

### 3.3 Host chrome

**Top bar.** Host name. `StatusPill` for link. Herdr badge if `backend == herdr`. Gear → Host settings. No Connect button.

**Bottom tabs.** Agents (person), Workspace (diff/project), More (ellipsis). Badge on Agents = `attentionCount` (Blocked panes + ACP `AwaitingPermission`).

**Connecting with cache.** Tabs work; lists show last Agents/Review/Tasks; composer/keys disabled; pill = Connecting.

**Never connected.** Agents empty state: "Connecting…" if link is Connecting, else "This Host has not connected yet." Workspace/More: same, no fake project picker.

### 3.4 Host settings (sheet)

Single owner for: Role (Observer/Controller), Push toggle (if `capabilities.push`; Android distributor copy unchanged), Edit connection, Disconnect, Unpair (confirm). Close = dismiss.

### 3.5 Agents list

**Header (compact, not a second dashboard).** If `attentionCount > 0`, a full-width accent card: "N waiting for you" → opens the first Blocked/permission conversation. Else a one-line Mission strip: `Working · Blocked · Done` counts, optional tokens/cost. Tapping the attention card is the fast path.

**Row anatomy (`AgentRow`):**

```
[status color bar 3dp]  Name                    [Blocked] [Pane|ACP]
                        project · branch
                        last line (1, ellipsis)            3m
```

| Slot | Source |
|---|---|
| Status color | `agentBlocked` / `agentWorking` / `agentIdle` / `agentUnknown` (`Done` uses idle) |
| Name | pane `name ?: agent ?: "Agent"`; ACP `agentName` |
| Type badge | Pane / ACP |
| Project | `workspaceName ?: project ?: workspace` |
| Last line | Cached Transcript last non-empty line if this pane was opened this session; else ACP last message; else omit. **Do not** `agent.read` every row. |
| Blocked badge | Pane `Blocked` or ACP `AwaitingPermission` |
| Time | Relative, only if `updatedEpochMs` is known (ACP start; else omit) |

Sort groups: Waiting (Blocked + ACP permission) → Working → Idle/Unknown → Done. ACP live session sits in Waiting or Working by `AcpRunState`.

**FAB.** "New agent" if `capabilities.acpSession` (Controller). Opens ACP launch sheet (agent kind + cwd, default cwd = first workspace). Empty list + ACP: `LaunchAgentCard` instead of a dead empty.

**Resumable sessions.** Section below, same as today (`agent.sessions` / Resume). Not mixed into live rows.

**States.** Empty connected: "No Agents. Launch one, or start one in Luvus." Error banner. Pull-to-refresh calls `show(Agents)`.

**Primary action.** Open conversation. FAB = launch ACP.

### 3.6 Conversation (pane Agent)

One screen for "talk to this Agent". Not a form with chips.

**Top bar.** Back. Title = name. Overflow: Name, Fork, (no Terminal here). Project name is a subtitle tappable → Workspace/Review with that `workspace_id` set.

**Body (column):**

1. Optional compact meta: status pill, branch, usage. cwd in overflow or a single muted line, not a labeled field stack.
2. Error / Unconfirmed banners.
3. **Segmented: Transcript | Terminal.** Default Transcript. Switching to Terminal starts observe; leaving stops it (ADR 0001).
4. Transcript: wrap on, selectable, sans-unfriendly → **mono, wrap, full width**. Parse ANSI. Jump-to-latest pill if scrolled up. New suffix highlight may stay.
5. **Sticky `BlockedCard`** (above composer) when status is Blocked:

   - Title: "Blocked — answer"
   - Body: last Transcript question if it looks like yes/no; else "This Agent is waiting."
   - Buttons: **Yes** · **No** (Agent prompt `"y"` / `"n"`) when yes/no; always **Enter** · **Esc** as Agent keys.
   - Custom: focused field on the card or the composer below; sending is an Agent prompt.
   - **No confirmation dialog** when the Agent is already Blocked. Unconfirmed banner if the result never arrives.
   - Observer: card visible, actions disabled, caption "Observer — can't answer".

6. Composer (Transcript only): rounded field, send. Placeholder "Agent prompt". Disabled while sending/unconfirmed/observer.

**Terminal segment.** Existing `TerminalPane` restyled with tokens (no `Color(0xFFFFC66D)`). **Wrap toggle** (default on for phone). Control / Observing / Conflict using `link*` / `agent*` roles. Key chips (arrows, Ctrl-C, Tab) only here. Observe only while this segment is selected.

**Name / Fork.** Sheets/dialogs as today, from overflow.

**Back.** `closeAgent()` — does not reset Host tabs; does not stop a Terminal observe that isn't showing.

### 3.7 Conversation (ACP)

Same chrome as 3.6 (title, overflow, transcript column, sticky card, composer). Differences:

- No Terminal segment.
- Overflow: Cancel turn, End session.
- Transcript items: user bubble (end-aligned, primaryContainer), agent text (start, surface), thought (muted, collapsible), tool row, turn divider. Reuse current ACP item types.
- Plan: collapsible card under the title when non-empty.
- Sticky card when `AwaitingPermission`: title = permission title, options as buttons mapped by `AcpPermissionKind` (allow = primary / live, reject = error). This **is** the Blocked card for ACP.
- Back = **hide** (`viewing = false`). Session keeps running; list shows the ACP row. End session = `closeAcp()`.
- Launch sheet stays a sheet; success sets `viewing = true` and pushes this screen.

### 3.8 Workspace

**Top.** Project picker chips (`ProjectChips`) when `projectChoices().size != 1`. If `needsProjectPick()`, body is `EmptyState("Select a project", …)` — never TUI focused workspace.

**Segments.** Review | Tasks. Selecting a segment calls `setSection` + `show` for `Review` / `Tasks` (existing loaders, including Review's `workspace.focus` locator).

**Review (file list).** Repo · branch title. Layer segmented control. "Files with notes" filter. Diff rows. Notes drawer / send-notes as today. Empty: "No Diff for this project." Pull-to-refresh.

**Diff file.** Mono hunks; add/del via `diffAdd` / `diffDel` tokens (not ad-hoc greens). Tap line → add Review note sheet. Resolve / reopen / remove / send notes unchanged.

**Tasks.** Project-filtered `projectTasks()`. Rows: title, status pill, claim/complete/retry/delete with existing confirms. FAB add. Empty: "No Tasks for this project" + Add. Host-wide dump is **not** shown when a project is selected.

**Jump from Agent.** Project subtitle sets `selectedWorkspaceId` and selects Workspace/Review.

### 3.9 More

A grouped list, not a dumping ground of equal tabs:

1. **Project tools** — Files, Search, Worktrees (disabled/hidden per capabilities; subtitle "Needs a files.tree workspace param" may stay as caption if unlabeled).
2. **Host** — Automations, Layout.
3. **This device** — Settings (opens Host settings).

Each row pushes the existing surface. Layout remains the pane picker for shell panes with no Agent; destructive close still confirms. Automations keep enable/disable/run/editor/history/rebind.

**Files / Search / Worktrees.** Same actions as today (open/reveal, query/activate, create/open/remove). Wrap them in the shared empty/error/loading. Mono for paths.

### 3.10 Platform notes

| | Android | iOS |
|---|---|---|
| Hosts + Host | `NavDisplay` stack; ≥600dp list \| detail | `NavigationSplitView` |
| Conversation | New `AgentRoute(id)` / `AcpRoute` on the back stack (not a swap inside the Agents tab) | `navigationDestination` (pane id or `acp`) — **not** `fullScreenCover` |
| More | Tab content = list; push sections | Tab content = list; push sections (retire `moreSurface` sheet as the only entry) |
| Pair | Stack route | Sheet (ok) |
| Settings | Modal bottom sheet | Sheet |
| Tabs | `NavigationBar` × 3 | `TabView` × 3 |
| Dynamic color | Material You; semantic agent/link colors stay brand (not wallpaper) | System `Color` + `DesignTokens` extras |

---

## 4. Design tokens

Seed: `docs/brand-spec.md`. Android maps seed → Material 3 when dynamic color is off; when dynamic color is on, **surfaces/primary follow the system** and the extended roles below stay brand so Blocked/Live remain recognizable.

### Color roles

| Role | Light | Dark | Use |
|---|---|---|---|
| `canvas` | `#F4F0EA` | `#161412` | Screen bg |
| `surface` | `#FFFBF6` | `#1E1B18` | Cards, rows |
| `ink` / `inkMuted` | `#1C1916` / `#6A635C` | `#F3EDE6` / `#A39B93` | Text |
| `accent` | `#C45C26` | `#E07A42` | Primary CTA, Blocked emphasis |
| `linkLive` | `#2F6F4E` | `#5BA87A` | Link live, Herdr ok |
| `linkConnecting` | `#3D6B99` | `#7BA3C9` | Connecting, Tailnet |
| `linkStale` | `#B56A1B` | `#E09A4A` | Stale, warnings, terminal truncated |
| `linkOffline` | `#8A837C` | `#8A837C` | Offline |
| `agentIdle` | inkMuted | inkMuted | Idle / Done |
| `agentWorking` | `linkConnecting` | `linkConnecting` | Working, ACP Working |
| `agentBlocked` | accent | accent | Blocked, ACP permission |
| `agentUnknown` | `linkOffline` | `linkOffline` | Unknown |
| `diffAdd` | `#0B6E3F` | `#81C784` | Diff add |
| `diffDel` | `#B71C1C` | `#EF9A9A` | Diff del |
| `terminalBg` / `terminalFg` | `#1A1815` / `#E8E2D8` | same | Terminal only |

Android: put link/agent/diff/terminal on `LuviaExtendedColors` (`LuviaTheme.extended`). **Forbidden:** raw `Color(0xFFFFC66D)` (and friends) in `Screens.kt` / Review. iOS: add the same names to `DesignTokens`.

### Typography

- Display (empty Hosts, Pair titles): serif (New York / `FontFamily.Serif`).
- UI: Material / SF Pro semantic styles. Max four sizes in a screen (title, body, label, caption).
- Mono: JetBrains Mono NL Nerd **only** for Transcript, Terminal, Diff hunks, cwd, fingerprints, pair command.

### Spacing and radius

8pt grid: 4 / 8 / 16 / 24 / 32 / 48. Card padding 16. Section gap 24. Radius 8 / 12 / 16 (already `LuviaShapes` / `DesignTokens.Radius`). Min tap 48dp / 44pt.

### Components (shared visual language)

| Component | Responsibility |
|---|---|
| `StatusPill` | Link or Agent status; color from roles; non-interactive |
| `AgentRow` | Anatomy in §3.5 |
| `BlockedCard` | Sticky answer / ACP permission |
| `EmptyState` | Title, message, optional CTA |
| `SectionHeader` | List group label |
| `KeyChip` | Terminal keys only |
| `HostAvatar` | Letter + link-dot |
| `AttentionBanner` | "N waiting for you" |
| `UnconfirmedBanner` | Keep; restyle to errorContainer |
| `MissionStrip` | Compact counts |
| `ProjectChips` | Keep |
| `TypeBadge` | Pane / ACP / Tailnet / Herdr / Observer |

Android: new `androidApp/.../ui/Components.kt`. iOS: `Components.swift` or extensions on existing views. Do not leave a second private `StatusPill` in Automations.

---

## 5. Shared-state changes (`shared/`)

Small and additive. `HostUhp` / `HostUhpState` remain the only surface both apps collect.

### Add in `HostUhpState.kt`

```kotlin
public enum class AgentKind { Pane, Acp }

public data class AgentEntry(
    public val id: String,          // paneId or "acp:{sessionId}"
    public val kind: AgentKind,
    public val name: String,
    public val status: AgentStatus, // ACP mapped: see below
    public val projectLabel: String?,
    public val lastLine: String?,
    public val updatedEpochMs: Long?,
    public val paneId: String? = null,
    public val acpSessionId: String? = null,
)
```

On `HostUhpState`:

- `fun agentEntries(): List<AgentEntry>` — pane `agents` + ACP row when `acp.open`.
- `fun attentionCount(): Int` — pane Blocked + (ACP `AwaitingPermission` if open).
- `fun waitingEntries(): List<AgentEntry>` — `attentionCount` rows, Blocked/permission first.

ACP → `AgentStatus`: `AwaitingPermission → Blocked`, `Working|Starting → Working`, `Ready|Idle → Idle`, `Exited → Done`.

`lastLine`: last non-empty line of `agentDetail.transcript` when `paneId` matches; else last ACP `Message` text; else `null`.

### `AcpState`

Add `viewing: Boolean = false`.

- `open && viewing` → Conversation shown.
- `open && !viewing` → row on Agents list, session alive.
- Launch success → `open = true`, `viewing = true`.
- Back → `viewing = false` only.
- End session → existing `closeAcp()`.

### `HostUhp`

```kotlin
public fun viewAcp()     // viewing = true if open
public fun hideAcp()     // viewing = false; do not close the stream
```

`launchAcp()` already opens; on success also `viewing = true`. `closeAcp()` sets `viewing = false`.

### Do not

- Change `HostSection` cases (tests and loaders depend on them).
- Add a global inbox store.
- Add auto-connect to `HostManager` (UI calls `connect`).
- Call `agent.read` for every list row.
- New Gradle dependencies.

### Tests (`./gradlew :shared:jvmTest`)

Extend `HostUhpTest.kt`:

1. `agentEntries` includes only panes when ACP closed.
2. Open ACP appends one `AgentKind.Acp` row; status maps permission → Blocked.
3. `attentionCount` = Blocked panes + ACP permission (not Working).
4. `hideAcp` keeps `open` and transcript; `closeAcp` clears open.

Existing `visibleSections` / project locator tests stay.

---

## 6. Implementation plan

Do not edit `iosApp/` in the Android/shared pass. iOS implements from this file after shared lands.

### 6.1 Shared (both platforms blocked on this)

| File | Task |
|---|---|
| `shared/.../HostUhpState.kt` | `AgentKind`, `AgentEntry`, `agentEntries()`, `attentionCount()`, `waitingEntries()`; `AcpState.viewing` |
| `shared/.../HostUhp.kt` | `viewAcp()`, `hideAcp()`; launch/close set `viewing` |
| `shared/.../internal/uhp/` ACP board | Set `viewing` on launch success / close; add hide/view |
| `shared/.../HostUhpTest.kt` | Tests in §5 |

### 6.2 Android

| File | Task |
|---|---|
| `ui/theme/LuviaTheme.kt` | Extend `LuviaExtendedColors` with agent/link/diff roles; keep dynamic color for M3 scheme |
| `ui/Components.kt` | **New.** StatusPill, AgentRow, BlockedCard, EmptyState, SectionHeader, KeyChip, AttentionBanner, TypeBadge, MissionStrip |
| `ui/UIModels.kt` | Host chrome tabs helper if needed; `attentionCount` on `HostUiModel` already has `blockedAgents` — map from `attentionCount()` when binding |
| `ui/LuviaNavigation.kt` | Auto-connect on `HostRoute`; `AgentRoute` / `AcpRoute` on back stack; wire `hideAcp` vs `closeAcp`; three-tab Host |
| `ui/Screens.kt` | Host list row without Connect button; Host chrome StatusPill + gear; More list; Terminal colors → tokens; Host settings unchanged owner |
| `ui/AgentScreens.kt` | Unified list from `agentEntries()`; conversation layout §3.6; BlockedCard; Name/Fork overflow; no confirm when Blocked |
| `ui/AcpScreens.kt` | Conversation matches pane; Back = hide; End = close; permission uses BlockedCard |
| `ui/ReviewScreens.kt` + `TaskScreens.kt` | Workspace shell (project + Review/Tasks segments); diff colors → tokens |
| `ui/FilesScreens.kt` `SearchScreens.kt` `WorktreeScreens.kt` `LayoutScreens.kt` `AutomationScreens.kt` | Shown from More; EmptyState; delete private StatusPill |
| `ui/Previews.kt` | Hosts empty, Host connecting+cache, Agent row Blocked, BlockedCard, Workspace pick |

Keep reachable: pairing, edit connection, unpair, push, Herdr badge, sessions/resume/fork/name, review notes, task mutations, automations, files/search/worktrees/layout, terminal control.

`./gradlew :androidApp:assembleDebug` (and `:androidApp:testDebugUnitTest` if tests exist).

### 6.3 iOS (separate worker)

| File | Task |
|---|---|
| `DesignTokens.swift` | Same color roles as §4; Space already matches |
| New `Components.swift` | StatusPill, AgentRow, BlockedCard, EmptyState, TypeBadge, AttentionBanner |
| `ContentView.swift` / `HostSidebarView.swift` | Auto-connect on selection; sort by attention; drop reliance on a Connect control |
| `HostDetailView.swift` | Tabs: Agents, Workspace, More (not Review/Tasks/Automations). Gear → settings. StatusPill. ACP via `navigationDestination`, not `fullScreenCover` |
| `AgentViews.swift` | `AgentRow` anatomy; conversation §3.6; BlockedCard; overflow Name/Fork; Terminal wrap toggle |
| `AcpViews.swift` | Same conversation; hide vs close; launch sheet unchanged |
| `ReviewViews.swift` + `TaskViews.swift` | Workspace container with picker + segments |
| `MoreViews.swift` | Tab root list: Files, Search, Worktrees, Automations, Layout, Settings. Retire sheet-as-only-entry (`MoreSurface` can remain as push values) |
| `AutomationViews.swift` `PairHostView.swift` | Tokens; Automations opened from More |
| `UIModels.swift` | Map `AgentEntry`; HostSection tab cases if the Swift enum still lists four primary tabs — reduce to three chrome tabs |
| `AppModel+HostUhp.swift` `AppModel+Acp.swift` | `connect` on select; `hideAcp` on back |

### 6.4 Out of scope

Push/Live Activity copy, Herdr protocol, new UHP methods, `files.tree` workspace param, spawning pane Agents from the phone (still ACP-only).

### 6.5 Follow-up doc

After both platforms ship, patch `docs/surface-model.md` "Tab bars" to:

```
Host:    Agents | Workspace | More
Agent:   Transcript | Terminal
```
