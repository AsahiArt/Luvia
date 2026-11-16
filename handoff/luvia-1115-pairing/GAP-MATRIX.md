# Pairing page gap matrix · 2026-11-15

Written before source edits. Base is PR59 `fe3a4da0185b95a116f81af0a959ca5cc712dfba` (tree `9e200ab9bc9475b6042dc2cc603f054e96398404`), clean. No merge or rebase of PRs 52 / 53 / 58 / 59.

This file is a source-and-pixel audit. It is not a visual pass, not a design acceptance, and not a device-session result.

## Frames and sources

| Frame | Status | Visual used | sha256 | Not used |
|---|---|---|---|---|
| `lv-and-01-pair` | finalized, implement now; section delta pending acceptance | orig spec `png-orig-spec` / attached `lv-and-01-pair.png` (780×1688, `@2x`, 1 dp = 2 px) and checked-in `design/pencil/png/lv-and-01-pair.png` | orig `b8daede8bf7bb86c38de535386aa36da517177ccb5be7f0b91d4a77fbe5bd39b`; checked-in `b8a8070254d28e2cf895685942d7d04bb0449f88e212f4e8442604b2884478d2` | `cmp-2026-11-13/after/sec-lv-and-01-pair.png` (`2e8505a8…`) |
| `lv-ios-01-pair` | finalized, implement now; full-frame delta pending acceptance | orig spec / attached `lv-ios-01-pair.png` (780×1688) and checked-in `design/pencil/png/lv-ios-01-pair.png` | orig `add2192cec107a77df26f50a9d6c9dbb3ce7a82343e1d03c745408ad71b3e972`; checked-in `5941500c4e1cf3298f588e8fbeb8eeec2e7af685e1cd0d3b4ebe7203ac2d66b6` | `cmp-2026-11-13/after/lv-ios-01-pair.png` (`090d12e7…`) and `after/sec-lv-ios-01-pair.png` (`85c7fa81…`) |

UX text: `docs/wow-CONCLUSION.md` (iOS pairing stays dark stage + blue sphere), `docs/final-rev-CHANGES.md` (Android pairing is the full-screen frame; token seed unchanged), `design/pencil/README.md` (figure/text conflict follows the text). `frame-index.json` marks both frames `implement_now` and forbids d474 after images. No finalized frame for an expired or invalid code (`missing_or_not_finalized.pairing-expired-or-invalid`).

Designer clarification `PAIRING-GAP5-CLARIFICATION` (2026-11-15) is a spec note, not acceptance of this PR. CSS px on the 390×844 board are not native dp, and `@2x` PNG pixels are not dp evidence. Old GAP5 is not passed as a whole.

## Old GAP5 after the 2026-11-15 clarification

Source of the four items: `GAP-android.md` for the device-name step, which that note itself says has no finalized frame. They are a designer supplement, not a Misaka-accepted frame.

| Item | Status | Evidence |
|---|---|---|
| Title and body share a 16 inset | Cancelled as a ritual-page change. Not a device-name spec. | Ritual `.scroll` is `padding:0 12px`. Hero and fingerprint cards are x=12, w=366. Title is board x=54, after the back control, not 16 from the screen edge. Source keeps `padding(horizontal = 12.dp)` on the ritual scroll. Device-name column stays `padding(horizontal = 20.dp)`. |
| Add the next page’s TopAppBar | Cancelled. Do not add a second bar. | Ritual `.tab` is already back + `配对新电脑` + help, above `.scroll`. Source is that one `Row`, not a `TopAppBar`. |
| Role note under the chips | Cancelled for this round. Copy is not finalized. | No device-name frame. Clarification does not set new wording. `pair_role_note` is not referenced. |
| Full-width「开始配对」, 56 high, 16 above the safe area | Cancelled for the ritual page. Not applied to the device-name step. | Ritual bar is QR width 76 + confirm, height 56, row `padding:8px 16px 24px`. The 24 is the gesture overlay. Source: `width(76.dp)`, `height(56.dp)`, `padding(horizontal = 16.dp)`, `padding(top = 8.dp)`, parent `navigationBarsPadding()` once. The extra bottom 16 that used to sit outside that inset is removed. |

## Still open, or missing evidence

| Item | Status |
|---|---|
| Ritual help mark | The `.tab` includes `{help}` (Pencil x=350, 24×24). Behavior and wording are explicitly deferred. `pair_help` is not a finalized label, so this page does not reference it. The `Row` still has back and title only. |
| `pair_help`, `pair_role_note` | Present via merge of `1c429da2a9616caabfc76c7029330de853adaf16` (tree `dfafa60ac2715358b89306e84187346fba127cf8`). Unused by this page. Shared owner can remove them. `strings.xml` was not edited here. |
| Fingerprint inner padding | HTML is `padding:14px 16px` (16 is inside the card). Source is `padding(14.dp)` on all sides. Not changed: board px are not automatically dp, and this note does not order a 16 rewrite. |
| Device-name / role step | No finalized frame. Left as it was. |
| iOS compile, both-platform real sessions, design and architecture review | Still open. This clarification is not PR 62 acceptance. |

## Other proven source gaps

| Id | Evidence | Fix in this change |
|---|---|---|
| AND-TILE | At y=920, orig and checked-in both show light tiles with ink `(22, 29, 28)` and only the last tile filled `(0, 106, 98)` (x≈621–710). `ShortCodeEntry` paints every filled index, and the next empty index, with `primary`. | Highlight only the last entered digit. Length 0 still highlights index 0, which is the current empty-slot behavior. Earlier digits use `surface` / `onSurface`. |
| AND-AX | `RoleChip` switches `Button` / `FilledTonalButton` and never sets `semantics.selected`. | `selectableGroup` on the row. Each chip sets `selected` from `role == HostRole.Controller` or `role == HostRole.Observer`. Click still assigns that `HostRole`. |
| IOS-AX | `roleChip` fills the selected capsule and does not add `.isSelected`. The help button already does, only while setup is open. | Add `.isSelected` when `role == value`, and remove it otherwise. `begin(role:)` is unchanged. |
| IOS-VISUAL | `PairField` is a dark stage, dotted blue sphere, solid blue arc, laptop, timer. The orig PNG’s `#` arc is continuous, not a dashed stroke. Subtitle, tiles (last one blue), fingerprint row, blue confirm, scan button, back, and help are already in `PairingView`. | No repaint. No d474 colors. |

## Not gaps (do not invent)

| Mock detail | Why it stays out |
|---|---|
| `macOS`, `12 ms`, 「电脑端已运行」 | `PairingCode` has name, user, port, bridge, host keys, hints, ids, device key, role, optional fqdn. No OS label, latency, or “host already running” flag. |
| iOS command, short-host, and paste fields below the fingerprint | Not in the static frame. They are the live short-code and `luvia2:` path. Removing them would drop pairing, not match the frame. |
| Expired / invalid short code | No finalized frame. Existing error copy stays. |
| Dashed iOS progress ring | README describes how Pencil stroked a CSS dash. The approved orig PNG shows a solid arc plus the dotted sphere. Current `PairField` matches that. |

## Tests this change can actually run

| Check | Where | Limit |
|---|---|---|
| `pairingTileHighlighted` for lengths 0…`SHORT_CODE_LENGTH`, exactly one of indexes 0…5 | `PairingScreenRulesTest` | Lengths above 6 are not specified and are not tested. |
| Existing shared pairing tests | `PairingTest`, `PairingConnectTest`, `GoldenPairingCodeTest` | Not re-run in the cleanup commit. Thresholds unchanged. |
| Android Kotlin compile | `:androidApp:compileDebugKotlin` plus the unit-test compile | No SDK install and no license acceptance. |
| Swift compile / XCTest / simulator | — | Not available on this Linux host. iOS is not built, not tested, and not approved. |

## Ritual padding literals after this correction

Board numbers below are CSS px on the 390×844 artboard. The Kotlin literals are what the file writes. They are not a claim that px equals dp, and not a visual pass.

| Layer | Source literal | Board note |
|---|---|---|
| Screen column | `statusBarsPadding()` | Status overlay is board height 36. Not a second top bar. |
| Top row | No horizontal padding. Back is an `IconButton` when `onBack` is set, else `Spacer(width = 12.dp)`. Title `weight(1f)`. | `.tab` height 64, padding `0 4px`, gap 2. Title x=54. Help icon is in the HTML and is not drawn here. |
| Ritual scroll | `padding(horizontal = 12.dp)` | `.scroll` `padding:0 12px`. Left at 12. |
| Hero card | `padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 22.dp)` | `padding:18px 18px 22px`. Unchanged. |
| Fingerprint row | outer `padding(top = 10.dp)`, inner `padding(14.dp)` | Outer `margin-top:10px`. Inner HTML is `14px 16px`. Source stays 14 on every side. |
| Action row | `padding(horizontal = 16.dp)`, `padding(top = 8.dp)`, `height(56.dp)`, QR `width(76.dp)`, parent `navigationBarsPadding()` | `padding:8px 16px 24px`, QR 76, button 56. The 24 is the gesture layer, applied once by `navigationBarsPadding()`. No extra bottom 16. |
| Start step | `padding(horizontal = 20.dp)`, `navigationBarsPadding()`, begin button `height(56.dp)` | No finalized frame. Not restyled. |

## Run record (this host)

Serial, one Gradle invocation at a time, `--max-workers=1 --no-daemon`. No SDK install. No license acceptance. `ANDROID_HOME` and `ANDROID_SDK_ROOT` were unset. No `android.jar` on the machine. `swiftc` and `xcodebuild` are not on `PATH`.

| Command | Result |
|---|---|
| `:shared:jvmTest` filtered to `PairingTest`, `PairingConnectTest`, `GoldenPairingCodeTest` | Did not run. Configuration of `:shared` failed: `SDK location not found`. Log: `logs/shared-pairing-jvmTest.log`. |
| `:androidApp:compileDebugKotlin` and `:androidApp:testDebugUnitTest` filtered to `PairingScreenRulesTest` | Did not compile or run. Same missing SDK during `:shared` configuration. Log: `logs/android-pairing-compile-and-unit.log`. |
| Swift compile / XCTest | Missing. iOS is not built, not tested, and not approved. |

Thresholds in the existing pairing tests were not edited. CI for `1289abb` and `f6b0856` is not a pass. This correction does not re-run the missing-SDK Gradle configuration. Only CI on the new head counts. The strings merge is `1154474115aca4d228b079d5740ca05bc50aafe1`.

## Gates left open

Design review, architecture review, and real-device shallow and deep genuine-session checks stay open. Fixture pixels are not a device pass. Android compile and the shared pairing tests still need an SDK that this host does not have. No host deploy, network, auth, private keys, or manual Mac CI.
