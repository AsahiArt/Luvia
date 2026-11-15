# Pairing page gap matrix · 2026-11-15

Written before source edits. Base is PR59 `fe3a4da0185b95a116f81af0a959ca5cc712dfba` (tree `9e200ab9bc9475b6042dc2cc603f054e96398404`), clean. No merge or rebase of PRs 52 / 53 / 58 / 59.

This file is a source-and-pixel audit. It is not a visual pass, not a design acceptance, and not a device-session result.

## Frames and sources

| Frame | Status | Visual used | sha256 | Not used |
|---|---|---|---|---|
| `lv-and-01-pair` | finalized, implement now; section delta pending acceptance | orig spec `png-orig-spec` / attached `lv-and-01-pair.png` (780×1688, `@2x`, 1 dp = 2 px) and checked-in `design/pencil/png/lv-and-01-pair.png` | orig `b8daede8bf7bb86c38de535386aa36da517177ccb5be7f0b91d4a77fbe5bd39b`; checked-in `b8a8070254d28e2cf895685942d7d04bb0449f88e212f4e8442604b2884478d2` | `cmp-2026-11-13/after/sec-lv-and-01-pair.png` (`2e8505a8…`) |
| `lv-ios-01-pair` | finalized, implement now; full-frame delta pending acceptance | orig spec / attached `lv-ios-01-pair.png` (780×1688) and checked-in `design/pencil/png/lv-ios-01-pair.png` | orig `add2192cec107a77df26f50a9d6c9dbb3ce7a82343e1d03c745408ad71b3e972`; checked-in `5941500c4e1cf3298f588e8fbeb8eeec2e7af685e1cd0d3b4ebe7203ac2d66b6` | `cmp-2026-11-13/after/lv-ios-01-pair.png` (`090d12e7…`) and `after/sec-lv-ios-01-pair.png` (`85c7fa81…`) |

UX text: `docs/wow-CONCLUSION.md` (iOS pairing stays dark stage + blue sphere), `docs/final-rev-CHANGES.md` (Android pairing is the full-screen frame; token seed unchanged), `design/pencil/README.md` (figure/text conflict follows the text). `frame-index.json` marks both frames `implement_now` and forbids d474 after images. No finalized frame for an expired or invalid code (`missing_or_not_finalized.pairing-expired-or-invalid`).

Attached images match the orig-spec hashes above, not the checked-in exports. Both orig and checked-in were measured. Tile and inset conclusions below hold on both Android files.

## Old Android GAP5, rechecked

| Old item | Approved pixels | Current `PairingScreen.kt` | Verdict |
|---|---|---|---|
| 16 dp title/body alignment | Hero card and fingerprint card left edge x=24 px = **12 dp**. Title glyphs start near x=111 px (55.5 dp), after the back icon at x≈45 px. The title is an app-bar title, not a 16 dp body inset. | Ritual scroll is `padding(horizontal = 12.dp)`. Start step is 20 dp and is not this frame. | Not a gap. Forcing 16 dp on the card would leave the approved art. |
| Missing top app bar | Title band y=117–154 has a back glyph and the title. | `Row` with back (`R.string.back`) when `onBack != null`, plus `R.string.pair_new_computer`. | Bar is present. Do not replace it. |
| Missing help | Trailing circle x≈704–743, y≈116–152, about 20 dp, right inset about 18 dp. | No trailing control on that row. | Open. Blocked on strings. See owner request. |
| Role explanation | The approved frame does not draw the explanation. iOS `PairingView` already says the name and role are written into the pair command. Android start step has `R.string.pair_intro` and the two role labels, and no equivalent sentence. | Chips call `onBegin(label, role)`. | Open as copy. Blocked on strings. Chips stay. |
| 56 dp full-width bottom CTA, 16 dp safe inset | Action row y=1528–1639 is 112 px = **56 dp**. Widest outer span x=32–747 is **16 dp** from both screen edges. The row is a QR control plus the confirm button, not one solid button. Pixels below the row are the device-frame corner, not a second inset. | `height(56.dp)`, `padding(horizontal = 16.dp)`, `bottom = 16.dp`, parent `navigationBarsPadding()`. | Already matches. Do not change. |

## Other proven source gaps

| Id | Evidence | Fix in this change |
|---|---|---|
| AND-TILE | At y=920, orig and checked-in both show light tiles with ink `(22, 29, 28)` and only the last tile filled `(0, 106, 98)` (x≈621–710). `ShortCodeEntry` paints every filled index, and the next empty index, with `primary`. | Highlight only the last entered digit. Length 0 still highlights index 0, which is the current empty-slot behavior. Earlier digits use `surface` / `onSurface`. |
| AND-AX | `RoleChip` switches `Button` / `FilledTonalButton` and never sets `semantics.selected`. | `selectableGroup` on the row. Each chip sets `selected` from `pairingRoleIsSelected`. Click still assigns `HostRole` and passes it to `onBegin`. |
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
| `pairingTileHighlighted` for lengths 0…6, exactly one of indexes 0…5 | `androidApp` unit test | JVM. Not a screenshot. |
| `pairingRoleIsSelected` is exclusive for controller and observer | same test | Predicate the chips read. Not a TalkBack session. |
| Existing shared pairing tests | `PairingTest`, `PairingConnectTest`, `GoldenPairingCodeTest` | Run serially at this head. Thresholds unchanged. |
| Android Kotlin compile | `:androidApp:compileDebugKotlin` plus the unit-test compile | No SDK install and no license acceptance. |
| Swift compile / XCTest / simulator | — | Not available on this Linux host. iOS is not built, not tested, and not approved. |

## Strings owner request (do not edit `strings.xml` here)

Add these keys. Do not change existing ones, and do not treat this as design acceptance.

- `pair_help` = `设备名称和角色`  
  Content description for the trailing help control. Same label as iOS `PairingView` help.
- `pair_role_note` = `名称和角色会写进配对命令。改完后，重新复制命令到电脑。`  
  Same sentence already shown in the iOS setup card.

After those exist, the Android ritual help control can open the existing device-name field and role chips plus `pair_role_note`. This change does not hardcode that copy and does not add the button with a wrong description.

## Run record (this host)

Serial, one Gradle invocation at a time, `--max-workers=1 --no-daemon`. No SDK install. No license acceptance. `ANDROID_HOME` and `ANDROID_SDK_ROOT` were unset. No `android.jar` on the machine. `swiftc` and `xcodebuild` are not on `PATH`.

| Command | Result |
|---|---|
| `:shared:jvmTest` filtered to `PairingTest`, `PairingConnectTest`, `GoldenPairingCodeTest` | Did not run. Configuration of `:shared` failed: `SDK location not found`. Log: `logs/shared-pairing-jvmTest.log`. |
| `:androidApp:compileDebugKotlin` and `:androidApp:testDebugUnitTest` filtered to `PairingScreenRulesTest` | Did not compile or run. Same missing SDK during `:shared` configuration. Log: `logs/android-pairing-compile-and-unit.log`. |
| Swift compile / XCTest | Missing. iOS is not built, not tested, and not approved. |

Thresholds in the existing pairing tests were not edited. The new unit test was not executed here, so it is not a pass.

## Gates left open

Design review, architecture review, and real-device shallow and deep genuine-session checks stay open. Fixture pixels are not a device pass. Android compile and the shared pairing tests still need an SDK that this host does not have. No host deploy, network, auth, private keys, or manual Mac CI.
