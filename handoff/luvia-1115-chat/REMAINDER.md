# Remainder after this chat-page run

Not claimed as passing: full Swift compilation, Android compile, unit tests, independent review, real sessions, or either theme on a device.

## Not run

- `swiftc` / `swift` / `xcodebuild` are absent on this Linux machine. No iOS build and no visual pass.
- No `android.jar` and no `ANDROID_HOME` / `ANDROID_SDK_ROOT`. SDK and licenses were not installed.
- `./gradlew :androidApp:testDebugUnitTest :androidApp:compileDebugKotlin --offline` failed before configuration: Android Gradle Plugin 9.0.1 is not in the offline cache. Log: `validation.log`.
- `ChatDecisionSheetTest` and `ChatComposerChromeTest` were not executed.

## Behavior not verified on a device

- Material3 1.5 `rememberBottomSheetState` / `partialExpand` / `hide` / `expand` were not typechecked. `rememberStandardBottomSheetState` was not used: in this library it is deprecated and can drop `PartiallyExpanded` when the sheet is shorter than half the screen.
- Whether 208dp actually fits grabber + two-line question + the recommended row depends on font scale. The column order puts that row first; the peek clips whatever does not fit. No second picture exists for middle or high.
- iOS 150ms horizontal crossfade is a token key plus a transition. Not observed.
- Exact 250ms sheet tween is not a parameter of the Material3 sheet. Android waits `max(250ms, hide())` before the composer fade.

## Left to other owners

- `session_gone` stays in `ChatFirstApp` and `ContentView`. Chat is not composed when the thread is gone.
- Queue index/total still count every pending thread, not only this session.
- iOS Diff frames 1–3 have models and `AppModel` calls and no SwiftUI pages. Not started. Android Diff was not expanded.
- `ContentView` still passes `nextQuestion` / `nextAgent`. The chat sheet no longer draws that other-session peek.
