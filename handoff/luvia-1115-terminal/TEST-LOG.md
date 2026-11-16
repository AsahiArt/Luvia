# Terminal page test log

Head under test: `cursor/terminal-light-board-9c9e` on top of `fe3a4da0185b95a116f81af0a959ca5cc712dfba`.
Commands ran one after another with `--no-parallel --max-workers=1`.
`ANDROID_HOME` pointed at a temporary SDK outside the repo. No `compileSdk`, NDK pin, or license file in the repo was changed.

## `:androidApp:compileDebugKotlin`

`BUILD SUCCESSFUL in 2m 16s`.

Gradle also reported, and this log does not treat it as an iOS pass:

```
w: Native task 'iosSimulatorArm64Test' is disabled
Task 'iosSimulatorArm64Test' for target 'ios_simulator_arm64' cannot run on the current host (linux-x86_64).
Reason: simulator tests require macOS

w: Disabled Kotlin/Native Targets
The following Kotlin/Native targets cannot be built on this machine and are disabled:
iosArm64, iosSimulatorArm64
```

`swiftc` and `xcrun` are not on this machine.

## `:androidApp:testDebugUnitTest` (filtered)

`BUILD SUCCESSFUL in 9s`.

| Class | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| `tech.asahiart.luvia.ui.TerminalOutlinedLineTest` | 2 | 0 | 0 | 0 |
| `tech.asahiart.luvia.ui.AnsiTextTest` | 6 | 0 | 0 | 0 |
| `tech.asahiart.luvia.ui.theme.LuviaThemeTest` | 5 | 0 | 0 | 0 |
| `tech.asahiart.luvia.TerminalPaneChoicesTest` | 4 | 0 | 0 | 0 |

## `:shared:jvmTest` (filtered)

`BUILD SUCCESSFUL in 16s`.

| Class | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| `tech.asahiart.luvia.thread.TerminalChromeTest` | 19 | 0 | 0 | 0 |
| `tech.asahiart.luvia.TerminalWaitSurfaceTest` | 3 | 0 | 0 | 0 |
| `tech.asahiart.luvia.thread.PlaytestTerminalTest` | 2 | 0 | 0 | 0 |

Existing thresholds were not edited. `LuviaThemeTest` and `AnsiTextTest` still expect the SPEC board, the `#FFDAD6` fill alpha band, and the dark wash alpha.
