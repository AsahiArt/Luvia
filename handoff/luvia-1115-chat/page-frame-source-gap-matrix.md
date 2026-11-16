# Chat page frame → source gap matrix

Written before chat code edits on this branch. Base is the integration commit, not `main`.

## Base

| Check | Result |
|---|---|
| HEAD | `11d423663f8ad1937c9386ef6ceb1c611b66dac6` |
| Tree at matrix time | clean (`git status --porcelain` empty) |
| Parent 1 | `3b650324cecc344cb73510f622215da66541488e` merge of `837191ee47649e6c1ffbb850e6077709b9f967d1` |
| Parent 2 | `33a5adbb5b78321f9d81bcd78f3d78e3b41a18c0` |
| Ancestor `837191ee` (PR52 ask parse) | yes |
| Ancestor `33a5adbb` (PR53) | yes |
| Ancestor `2eb6e0f1` (PR58) | yes |
| Ancestor `fe3a4da` (PR59) | yes |
| Ancestor `a1375f93` (main at integration) | yes |
| `ba9bd691` / `d4744771` commits | not in this git; they are pen sha256 values |

Visual baseline is ba9bd PNG + HANDOFF text. d474 is not an implementation baseline. Zip paths under `/workspace/...` are the designer machine; this run read the uploaded zip and the repo `design/pencil/png/` copies.

| Frame | Zip checked-in sha256 | Repo `design/pencil/png` sha256 | Orig-spec sha256 |
|---|---|---|---|
| `lv-and-03-chat` | `86293b38…f891b` | same | `d10759fb…84feb` |
| `lv-ios-03-chat` | `7394516a…2d37a` | same | `c2bd4506…f39d12` |
| `lv-ios-06-diff-1` | `7948747c…10d07` | same | (not the implementation baseline for this run) |
| `lv-ios-06-diff-2` | `e3f96d81…e8635` | same | |
| `lv-ios-06-diff-3` | `040bb5d4…04bd0` | same | |

`frame-index.json`: both chat frames `implement_now: true`, `nativeization: no-d474-full-export`. Missing id `decision-sheet-medium-large` is text-spec-only. Images are the low sheet. Middle and high come from HANDOFF §2, not from a second picture.

## Owned files

`ChatScreen.kt`, `ChatView.swift`, `ChatComposerChrome.kt`, `ChatFormat.kt`, plus a new exclusive helper/test. No SessionList, MachinesHome, filter bridge, pairing, terminal, theme, Glass, strings, router, ViewModel, UIModels, ScreenshotActivity, protocol, or design edits.

## Chat matrix

| Item | Source | Code before this run | Proven gap |
|---|---|---|---|
| Android trail (read / edit diff / run meter) | `lv-and-03-chat` low frame | `ChatScreen.kt` `ToolTrail`, `DiffCard`, `TestMeter` | No. Layout already follows the frame. No new trail chrome. |
| iOS trail chips | `lv-ios-03-chat` low frame | `ChatView.swift` `ToolStream` / `TrailChipRow` / `XcodeDiffCard` | No layout gap. |
| Trail live action while revoked | control / revoked / no live action when stale | iOS `canAsk` is controller and not offline only (`ChatView.swift` tool rows). Revoked can still be `.live`. | Yes. Gate the “交给 claude 修” send on the same revoked bit the composer already uses. Android trail has no send button. |
| 22% scrim | HANDOFF §2: delete the manual scrim | `ChatScreen.kt` scrim `alpha = 0.22f` over the list while `thread.ask != null` | Yes. Remove it. |
| Composer hidden while a decision is up | HANDOFF §2: one input, the sheet note | Android always composes `Composer` under `DecisionCard`. iOS already hides `composer` when `thread.ask != nil`. | Yes, Android only. |
| Non-modal sheet, drag, cannot dismiss | HANDOFF §2 Android `BottomSheetScaffold` + `skipHiddenState` behavior; iOS `.sheet` + detents + `interactiveDismissDisabled` | Both platforms pin a full card in the column. No detent, no drag handle, no system sheet. | Yes, both. |
| Low height | Text: Android 208dp, iOS 196pt. Frame is the low visual, not a second height. | Card grows with every option, note, and button. | Yes. Peek / `.height(196)` clips the same column. |
| Middle | Text only: iOS `.medium`; Android expanded = content height. Do not invent a third Android drag anchor. | Absent. | Yes, via the standard expanded state / `.medium`. |
| High | Text only: note focus. iOS `.large`. Android = keyboard lifts the expanded sheet. | Absent. | Yes. Focus calls `expand()` / sets `.large`. |
| Chat scroll and tap behind the sheet (read while deciding) | HANDOFF §2: no mask; low and middle leave the thread usable | Scrim plus a card that consumes the column. | Yes. Scaffold content and `presentationBackgroundInteraction` up through medium. |
| Other sessions’ stacked peek | iOS frame shows `2/2 · opencode · …` behind the card. HANDOFF §2: other sessions stay on the home stack; the next decision in this session crossfades inside the sheet (150ms) and returns to low. | `ChatView.decision` draws `peekLabel` from `nextQuestion` / `nextAgent` (global pending, passed by `ContentView`). | Text wins. Remove that stacked peek. Do not retarget `ContentView`. Keep the parameters so the call site still compiles. |
| Same-session next decision | HANDOFF §2 exit rules | Ask identity is whatever the parent passes. No crossfade, no return to low. | Yes. Crossfade 150ms and `partialExpand` / detent 196 when the ask token changes. |
| Answer exit | Sheet leaves, then the composer fades in | Android swaps the card off with the composer still visible. iOS drops the card and shows the composer immediately. | Yes. Hide the sheet, then fade the composer 150ms. Android uses standard `hide()` because `rememberStandardBottomSheetState` has no 250ms tween; composer waits until both 250ms and hide have finished. |
| Original question, two lines | `decisionQuestionText` on the cached ask only; HANDOFF two lines | Android `decisionQuestionText(ask.question)` + `maxLines = 2`. iOS `decisionQuestionText(thread:)` + `lineLimit(2)`. | No. Keep. Do not fall back to the session title or `等你确认`. |
| Note / free text | `askHasFreeText` (`freeTextNav != null`); PR52 `z` row is not an option | Note field only when options exist and `askHasFreeText`. | No rule gap. Keep that predicate (extract, don’t widen). |
| Empty options → terminal | PR52: a recognized block with no keys is an empty option list, not Yes/No | Both show `去终端回答` / `answer_in_terminal` when `options.isEmpty()`. | No. Do not synthesize Yes/No in the UI. |
| Revoked 38% ink | Existing chrome tests and `a96472a` | `composerOnSurface38` only when revoked. Decision card alpha 0.38 only when revoked. iOS sheet opacity 0.38 and composer prompt/send 0.38. | No rule gap. The new sheet must keep alpha 0.38 for revoked only. |
| Observer / offline / sending | `chatComposerChrome` | `decisionEnabled == inputEnabled`, which is false for observer, offline (`connection != Live`), and sending. Note copy is the observer string. Composer ink stays full. | No rule gap. Sheet actions stay on `decisionEnabled`. |
| `session_gone` | `R.string.session_gone`; iOS “会话不在了” | `ChatFirstApp.kt` and `ContentView.swift` replace the page when `openThread` is null. `ChatScreen` / `ChatView` are not composed, so they cannot send. | No gap inside owned files. Do not add a dead flag the router never passes. |

`read-later` in the task is this non-modal behavior: the thread stays readable and scrollable under the low and middle sheet. It is not a new “稍后” option. PR52 already drops an older “稍后” row (`ThreadTest.currentClaudeAskIgnoresOlderGrokOptions`). This run does not add one.

## iOS Diff three frames (read only)

No Diff page is implemented. This run does not add one and does not edit these files.

| Frame | Spec | Implementation boundary | Gap |
|---|---|---|---|
| `lv-ios-06-diff-1` file list | HANDOFF §3 `List(.insetGrouped)`, large title “Diff”, sections “n 个文件” and “批注 · n” | Models: `iosApp/iosApp/UIModels.swift` `DiffFileItem`. State: `AppModel.diffFiles`, `diffBranch`, `loadDiff()`. No SwiftUI list. | Page missing. |
| `lv-ios-06-diff-2` hunk | HANDOFF §3 file push, ↑↓ hunk jump, glass file buttons, 12/21 mono, green 14% / red 11% | Models: `DiffHunkItem`, `DiffLineItem`, `DiffFileDetail`. State: `AppModel.selectedDiff`, `openDiffFile`. No hunk view. | Page missing. |
| `lv-ios-06-diff-3` note sheet | HANDOFF §3 `.sheet` detents medium/large, grabber, focus → large, confirm discard, save inserts an unsent note | State: `beginAddNote`, `addReviewNote`, `notes`, resolve/reopen/remove/send. No note sheet view. | Page missing. |

Shared domain (`DiffFile`, `DiffHunk`, `DiffLine`, `DiffListResult`, review commands) already exists. Android has no approved Diff frames; `ChatScreen.DiffCard` is the in-trail card only. `ContentView` stages are pair / sessions / chat / terminal, not diff. Entry “看全部” on a Diff card is not in `ChatView` (the session-list “看全部” is the path scope). Building the three frames needs a router `Route.diff` owner and new view files. Out of this run.

## Integration-owner requests (not done here)

1. `session_gone` stays in `ChatFirstApp` / `ContentView`. No new Chat parameter.
2. Queue index/total still count every pending thread (`ChatFirstApp` pending list, `ContentView.pending`). HANDOFF §2 says the sheet queues this session only. Changing that count means changing the router, which this run does not own.
3. iOS Diff still needs a navigation owner. Not requested as a code change in this PR.

## Resolution in this branch

Only the Yes rows above. Trail layout, question text, two-line clamp, free-text predicate, empty-option terminal fallback, and the 38% / observer / offline chrome rules are unchanged.

| Gap | Change |
|---|---|
| Android 22% scrim | Removed. |
| Android composer under the decision | Hidden while the sheet is up. Fades in 150ms after the sheet has hidden and 250ms have elapsed. |
| Android sheet | `BottomSheetScaffold`, peek 208dp, `BottomSheetDefaults.DragHandle` and `ExpandedShape`, `surfaceContainerLow`, no scrim. `rememberBottomSheetState` keeps `PartiallyExpanded` (the deprecated `rememberStandardBottomSheetState` wrapper skips that anchor when the sheet is under half the screen). `Hidden` is vetoed unless an answer is leaving. Note focus calls `expand()`. Keyboard is `imePadding` on the scaffold. |
| iOS sheet | System `.sheet` with `.height(196)`, `.medium`, `.large`, visible grabber, scroll interaction, dismiss disabled, background interaction up through medium. No custom sheet background or corner. Note focus sets `.large`. |
| Same-session next ask | Android `AnimatedContent` 150ms and `partialExpand()`. iOS detent returns to 196 and the body is keyed by the ask token. |
| Other-session peek | Removed from `ChatView`. `nextQuestion` / `nextAgent` stay in the signature. |
| Revoked trail send | iOS `canAskTools` uses `canCompose`, which includes revoked. |
| Stale sheet actions | Android actions require `decisionEnabled` and a live ask. iOS option, continue, terminal, and note controls stay disabled when `canCompose` is false. Revoked sheet stays at 38% opacity and does not take hits. |
