# Pairing page gap matrix · 2026-11-15

Written before source edits. Base is PR59 `fe3a4da0185b95a116f81af0a959ca5cc712dfba` (tree `9e200ab9bc9475b6042dc2cc603f054e96398404`), clean. No merge or rebase of PRs 52 / 53 / 58 / 59.

This file is a source-and-pixel audit. It is not a visual pass, not a design acceptance, and not a device-session result.

## Frames and sources

| Frame | Status | Visual used | sha256 | Not used |
|---|---|---|---|---|
| `lv-and-01-pair` | finalized, implement now; section delta pending acceptance | orig spec `png-orig-spec` / attached `lv-and-01-pair.png` (780×1688, `@2x`, 1 dp = 2 px) and checked-in `design/pencil/png/lv-and-01-pair.png` | orig `b8daede8bf7bb86c38de535386aa36da517177ccb5be7f0b91d4a77fbe5bd39b`; checked-in `b8a8070254d28e2cf895685942d7d04bb0449f88e212f4e8442604b2884478d2` | `cmp-2026-11-13/after/sec-lv-and-01-pair.png` (`2e8505a8…`) |
| `lv-ios-01-pair` | finalized, implement now; full-frame delta pending acceptance | orig spec / attached `lv-ios-01-pair.png` (780×1688) and checked-in `design/pencil/png/lv-ios-01-pair.png` | orig `add2192cec107a77df26f50a9d6c9dbb3ce7a82343e1d03c745408ad71b3e972`; checked-in `5941500c4e1cf3298f588e8fbeb8eeec2e7af685e1cd0d3b4ebe7203ac2d66b6` | `cmp-2026-11-13/after/lv-ios-01-pair.png` (`090d12e7…`) and `after/sec-lv-ios-01-pair.png` (`85c7fa81…`) |

UX text: `docs/wow-CONCLUSION.md` (iOS pairing stays dark stage + blue sphere), `docs/final-rev-CHANGES.md` (Android pairing is the full-screen frame; token seed unchanged), `design/pencil/README.md` (figure/text conflict follows the text). `frame-index.json` marks both frames `implement_now` and forbids d474 after images. No finalized frame for an expired or invalid code (`missing_or_not_finalized.pairing-expired-or-invalid`).

Attached images match the orig-spec hashes above, not the checked-in exports. Pixel notes below are measurements only. They do not close old GAP5. The designer is still separating a 16 dp title/body inset from the frame’s 12 dp card.

## Old Android GAP5

Not closed. Screenshot pixels and the current source are not enough to call any of these resolved.

| Old item | What was measured | What the source does now | Status |
|---|---|---|---|
| 16 dp title/body alignment | On both Android PNGs the hero and fingerprint cards start at x=24 px (12 dp at `@2x`). Title glyphs start near x=111 px. | Ritual scroll is `padding(horizontal = 12.dp)`. Start step is 20 dp. | Open. Designer is clarifying 16 dp title/body against the 12 dp card. This page does not pick one. |
| Missing top app bar | Title band y=117–154 includes a back glyph and the title. | `Row` with back (`R.string.back`) when `onBack != null`, plus `R.string.pair_new_computer`. | Not treated as closed. Help is still missing from that row. |
| Missing help | Trailing circle about x=704–743, y=116–152. | No trailing control. | Open. Strings are not in this tree. See placement note. |
| Role explanation | The approved UX docs do not print the explanation. iOS `PairingView` has one hardcoded sentence. Android start step has `R.string.pair_intro` and the role labels. | Chips call `onBegin(label, role)`. | Open until `pair_role_note` exists on a known dependency SHA. |
| 56 dp full-width bottom CTA, 16 dp safe inset | Action row y=1528–1639 is 112 px. Widest outer span x=32–747. | `height(56.dp)`, horizontal 16 dp, bottom 16 dp, parent `navigationBarsPadding()`. | Not treated as closed from pixels or source alone. Padding was not edited. |

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

## Help placement (not built in this commit)

Shared owner `bc-fdcabbd6` is adding `pair_help` and `pair_role_note` on the integration branch. No dependency SHA was in this run. This branch does not reference those keys and does not edit `strings.xml`.

Where the control goes once that SHA is on this branch:

- Android: trailing icon on the existing top `Row` in `PairingScreen` (back, then `R.string.pair_new_computer`). That row is outside the draft split, so it shows on the start step and the ritual. The ritual is the frame that has the circle. The panel it opens is the device-name field and the two role chips already in `PairingStart`, plus the role note. It should not be a second copy of the ritual scroll.
- iOS: already placed. `chrome` is an `HStack` with `.padding(.horizontal, 16)` and `.padding(.top, 8)`; `helpButton` is the trailing `questionmark` control. It toggles `setupLayer` / `setupCard` (device name, role chips, and the note sentence). Role chips already use `.isSelected`.

Written UX docs do not specify that control or that sentence:

- `design/pencil/docs/wow-CONCLUSION.md` only keeps iOS pairing on the dark stage and blue sphere.
- `design/pencil/docs/final-rev-CHANGES.md` lists `android-01-pair` as the full-screen frame and does not mention help or a role sentence.
- `design/pencil/docs/final-rev-HANDOFF.md` has no pairing-help section.
- `design/pencil/README.md` points `lv-and-01-pair` at HTML that is not in the repo. Its UX excerpts do not mention help.
- `frame-index.json` gives both pairing frames the single state `pairing`.

The label `设备名称和角色` and the sentence `名称和角色会写进配对命令。改完后，重新复制命令到电脑。` are the hardcoded iOS `PairingView` help label and `setupCard` text. They are not a checked-in UX document. Android `R.string.pair_intro` is a different sentence.

## Run record (this host)

Serial, one Gradle invocation at a time, `--max-workers=1 --no-daemon`. No SDK install. No license acceptance. `ANDROID_HOME` and `ANDROID_SDK_ROOT` were unset. No `android.jar` on the machine. `swiftc` and `xcodebuild` are not on `PATH`.

| Command | Result |
|---|---|
| `:shared:jvmTest` filtered to `PairingTest`, `PairingConnectTest`, `GoldenPairingCodeTest` | Did not run. Configuration of `:shared` failed: `SDK location not found`. Log: `logs/shared-pairing-jvmTest.log`. |
| `:androidApp:compileDebugKotlin` and `:androidApp:testDebugUnitTest` filtered to `PairingScreenRulesTest` | Did not compile or run. Same missing SDK during `:shared` configuration. Log: `logs/android-pairing-compile-and-unit.log`. |
| Swift compile / XCTest | Missing. iOS is not built, not tested, and not approved. |

Thresholds in the existing pairing tests were not edited. CI for `1289abb9ed36d1e6d0c5b2b1a27a2324684f3993` was still in progress and is not a pass. The cleanup commit does not re-run those Gradle tasks.

## Gates left open

Design review, architecture review, and real-device shallow and deep genuine-session checks stay open. Fixture pixels are not a device pass. Android compile and the shared pairing tests still need an SDK that this host does not have. No host deploy, network, auth, private keys, or manual Mac CI.
