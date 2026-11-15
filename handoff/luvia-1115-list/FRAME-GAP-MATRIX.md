# List page frame → source → gap → verification

Audit written before source edits. Base `11d423663f8ad1937c9386ef6ceb1c611b66dac6` (tree `f9a147a6fdd1fc5881802b1db73b41ab2e34c2e2`). Ancestors present: PR52 `837191ee`, PR53 `33a5adbb`, PR58 `2eb6e0f1`, PR59 `fe3a4da`. Working tree was clean. Branch `cursor/list-decision-deck-e391` starts at that commit. Not branched from `main` (`a1375f93`).

Visual: `png-orig-spec` + `png-checked-in` (ba9bd). Interaction numbers: `docs/final-rev-HANDOFF.md`. Image vs text: text wins. d474 / `cmp-2026-11-13/after` not used. Android FilterChip stays (HANDOFF §5); merged filter row is unselected. No sample host catalog and no invented connection state.

Owned files: `SessionListScreen.kt`, `SessionListView.swift`, `HostBanners.kt`, `RouteChip.kt`, plus exclusive deck helper/tests. Not touched: MachinesHome, HostFilterBridge, HostSessionFilter, ChatScreen, ChatView, composer, theme, Glass, strings, router, ViewModel, UIModels, ScreenshotActivity, protocol, design.

| Frame | Source before edit | Gap (proven) | Verification |
|---|---|---|---|
| lv-and-02-home | `SessionsBody` draws `SessionFilterRow` then `HorizontalPager` of `DecisionPreview`. No `i/n`. Pager peeks the next card sideways (`contentPadding` end 72). Card color `tertiaryContainer`, radius 28, question `decisionQuestionText` max 2 lines, two option buttons or `answer_in_terminal` already match the card face. FilterChips + check + `selectableGroup` + scope chip already match §5 / PR53. Stats row, pi `preservesColor` mark, route chip only when `HostUiModel.route` is non-null, revoked banner then offline/reconnecting cache banner already match PR59. | Order is filters then cards; frame is section, stacked card, then chips. Deck is a horizontal pager, not HANDOFF §1.1 (peek y 6/12, scale 0.96/0.92 from bottom center, alpha 0.72/0.45, layers = min(2, cards behind), no peek and no counter when 1 or when the visible card is last). `scrolledContainerColor` is `surface`, not `surfaceContainer`. Filter row start inset is 12dp, §5 says 16dp. Filters are not a `stickyHeader`. | After fix: source inspection of deck order, counter, peek math, sticky header, scrolled color. Android unit test of deck math. No device frame. |
| lv-and-02b-flip | No `pointerInput`, no velocity, no rotation, no 220ms fly, no spring, no 20dp edge reject, no 0.3 single-card damp, no reduce-motion crossfade. Pager only moves horizontally and does not wrap with the §1.2 rules. | All 8 flip cases missing. §1.3 answer exit (scale 0.9 / fade 200ms) and 0-card group collapse 300ms also missing (`if (showCards)` just removes the block). | `DecisionDeckMath` tests lock threshold (`>` 1/3 of the larger axis), 800dp/s, single-card never flips, rotation clamp ±8 with 0 on pure vertical, edge band, peek counts, counter, wrap, answer index clamp. Gesture rendering not run on a device. |
| lv-ios-02-home | `sessionChrome` puts the segmented status control above the card. `stackedDecisions` draws empty rims (inset 14/28, y 10/20, height 36), always the first pending card, no `i/n`. Orange 1.5pt stroke, radius 28 continuous, question `decisionQuestionText` line limit 2, terminal button when options are empty, pi asset `agent-logo-pi` original rendering: matched. Scope lives in `toolbarTitleMenu` (`全部在线主机` from ContentView, not owned). Blue unread dot on `needsYou` rows matches the frame. Glass tab + search circle matches the ba9bd PNG. | Same stack/counter/order gap as Android. Frame shows a blue「全部」scope control on the section row; the title menu does not draw that word. Row title and decision question use fixed `.system(size:)` so Dynamic Type does not scale them. | Source inspection. iOS full compile is not available in this environment (no Xcode). Dynamic Type change is source-only. |
| lv-ios-02b-flip | `reduceMotion` is only applied to the filter highlight. No `DragGesture` on the card. | Same 8 flip cases, edge 20pt, slop 8, spring 0.35/0.85, 220ms ease-out, single-card ×0.3, wrap, a11y「下一张」「上一张」. | Same Swift functions as the Kotlin helper. Not executed (no iOS test host). |
| 0 / 1 / many cards | 0: block omitted with no 300ms height. 1: Android pager still has end padding; iOS hides rims but has no rule that a lone card damps drag. 2: last card still not a distinct “no peek, still show 2/2” state. Many: no behind-count cap. | §1.1 table. | Unit tests for peek count and counter. Collapse duration constant `COLLAPSE_MS = 300` used by `AnimatedVisibility`. |
| PR52 question / 2 lines / free-text / terminal | `decisionQuestionText(thread)` is the cached ask question only. Both cards `lineLimit` / `maxLines` 2. Empty `options` → 「去终端回答」 / `R.string.answer_in_terminal`. | No gap in owned card code. Do not re-parse the transcript here. | Left as-is. |
| PR53 status + scope ② | Android: status `FilterChip`s plus scope `FilterChip` menu (3 paths + 更多). iOS: segmented status + `toolbarTitleMenu` scope. | Do not replace FilterChip with the unselected merged row. Do not edit `HostSessionFilter` / `HostFilterBridge`. | Chips and menu stay. iOS adds a visible scope menu labeled「全部」for all-online, using the existing scope actions. |
| PR59 route + banners | `RouteChip` is Tailnet / Lan / Unknown from `ConnectionRoute` only. Banners: revoked (`pairingRevoked`) wins; else non-live shows cache banner; `Connecting` shows the retry spinner. | No gap. Classification stays on `HostUiModel` / `HostViewState`. | No banner/chip behavior edit. |
| Spacing, safe area, empty, search, unread, pi | Scaffold + navigation bar insets; iOS `safeAreaInset` tab bar; search field; empty/loading copy; pi official mark (not a purple P). Android gesture must not call `systemGestureExclusion` (§1.2). | Edge slop was the missing safe-area rule. Dynamic Type gap is the fixed iOS sizes above. Title-row「4 agents · 12 ms」is not on `SessionListScreen` (no latency parameter). iOS `latencyMs` exists but the system large title has no trailing slot. | Edge rule tested in math. Latency placement recorded as a caller dependency, not faked. |
| HANDOFF §4 P0-3 system `TabView` | iOS still uses the glass capsule + `ChromeHitShield`. The ba9bd home PNG shows that chrome, not a system tab bar. | Text (§4) and the later approved PNG disagree. Replacing the chrome would leave the approved frame and touch hit-testing that already stops row taps. | Not changed. Remainder: design/architecture review. |
| HANDOFF §5 sticky filter + scrolled bar | Filters sit outside the only list. Top bar scrolled color equals the resting color. | Proven against §5 / §4 P0-4. | Android: one `LazyColumn`, filter `stickyHeader`, `scrolledContainerColor = surfaceContainer`. |

Queue order stays the `pending` / `shown` order already supplied. There is no arrival timestamp on the page model; sorting here would invent one. New-card deferral is “while the finger is down, keep the frozen list”, then release. Shared files were not required for the deck math.

## What this branch changes

Owned only. `HostBanners.kt` and `RouteChip.kt` unchanged.

- Android: pager replaced by `DecisionDeck` (peek, `i/n` only when n>1, any-direction flip, 20dp edge, 8dp slop, 0.3 damp on one card, 220ms fly, spring 0.35/0.85, answer scale 0.9 / 200ms, 0-card collapse 300ms). Section then card then sticky `FilterChip` row. Start inset 16dp. `scrolledContainerColor = surfaceContainer`.
- iOS: real scaled peek cards, orange `i/n` capsule, same flip rules, section then card then segmented status, scope menu「全部」on the section row, Dynamic Type via `ScaledMetric` for the 16/18 sizes.
- Tests: `DecisionDeckTest` locks the HANDOFF numbers. Not executed here (no Android SDK; not installed, licenses not accepted). iOS full compile not run (no Xcode).

## Shared JVM failure — not fixed here

Worker on `1c429da2` (parent `11d4236` plus two strings): shared JVM 386 tests, 1 failure, 0 errors, 0 skipped.

`SessionListTest.offlineOrInvalidCardKeepsCachedQuestionNotSessionName` at `shared/src/commonTest/kotlin/tech/asahiart/luvia/thread/SessionListTest.kt:637`.

`assertNull(missing.ask?.question)` actual `"等你确认"`.

Scenario: blocked pane `tailnet-i` with no `panePreviews` and no `agentDetail` transcript. Title must stay `tailnet-i`. Cached preview path above it already keeps `Fork this session into current directory? [y/N]`. The miss path must not invent a question.

Trace:

1. `HostUhpState.threads` → `AgentSummary.toThread(hostId, cachedTranscriptFor(...))` in `shared/src/commonMain/kotlin/tech/asahiart/luvia/thread/AgentThread.kt`.
2. `cachedTranscriptFor` (lines 112–117) returns null. That part is correct.
3. `toThread` line 132: `ask = if (waiting) paneDecision(plain) else null` still calls `paneDecision` when `plain` is null.
4. `paneDecision` lines 298–301: `val question = hit?.text ?: DECISION_QUESTION_FALLBACK`. Constant line 67 is `"等你确认"`. Null transcript has no hit, so the Ask is built with that placeholder. `decisionQuestionText` in `SessionList.kt` only trims; it does not clear the placeholder, and line 637 fails before that call.

List screens only read `decisionQuestionText`. They cannot make `ask.question` null. No copy of this mapper was added.

Minimal shared edit (common owner, `AgentThread.kt` only):

```kotlin
ask = if (waiting && plain != null) paneDecision(plain) else null,
```

inside `AgentSummary.toThread`. Do not change `assertNull`, the question string, or `DECISION_QUESTION_FALLBACK`. Cached previews still go through `paneDecision`, so the PR52 question on the first half of the test stays. A null transcript no longer invents a card question (PR59 cache-miss).

Conflict the owner must resolve, not by weakening either test: `ThreadTest.blockedPaneWithoutTranscriptAsksToConfirm` (`ThreadTest.kt:74–77`, still green on this run) expects `DECISION_QUESTION_FALLBACK` for the same shape (Blocked, no preview, no transcript). That expectation contradicts line 637. The list test is the later one (`26046d4`). I did not edit `ThreadTest` or `AgentThread.kt`.

`ChatFirstApp.kt:136` `selected?.username` vs `HostUiModel.user` is the common owner's compile fix. Not touched.

