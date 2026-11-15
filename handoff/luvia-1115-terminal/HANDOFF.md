# Terminal page handoff · 2026-11-15

Isolated draft from exact PR59 `fe3a4da0185b95a116f81af0a959ca5cc712dfba` (`origin/engineer/ui-gap-chip-banner`). PR59, PR58, PR53, and PR52 branches were not rebased or force-pushed. No merge and no self-approval.

## What changed

- Android light failure rows now use the theme's Misaka fill (`#FFDAD6` at 55%), stroke (`#BA1A1A`), and ink (`#410002`). Dark rows keep the existing wash. The SPEC does not give a new dark stroke hex, so none was added.
- iOS terminal colors follow `terminal-light-bg-SPEC.md` instead of the historic dark PNG and the older `#1A1815` / `#E8E2D8` pair. Light is `#EEF5F2` / `#161D1C`. Dark is `#101615` / `#E4EEEB`. The page no longer forces dark.
- iOS pane labels get the HANDOFF P0-4 margin, capsule clip, and 12pt fade. The interrupt key reads `^C` and stays visually separate from latched ctrl.
- The light failure summary card is the SPEC error `#BA1A1A`. The dark glass card is unchanged.

Evidence and the row-by-row audit are in `GAP-MATRIX.md`. Commands and counts are in `TEST-LOG.md`.

## Shared requests for the integration owner

- iOS agent-versus-shell failure chip needs `ContentView` to pass the session agent and fix candidates, the way Android `ChatFirstApp` already does. This page still shows the approved iOS label 「让 claude 修」.
- System back and the zoom transition stay in `ContentView`'s stage stack. This page does not draw a d474 transition or mux D.
- No theme, string, router, or Glass edit was required. Android already had the SPEC tokens.

## Still needs a person and a device

- Real session, both light and dark, on an Android phone and an iPhone. Fixture output is not that check.
- Mac compile and simulator or device pass for the Swift files. Linux disabled the iOS Kotlin/Native targets and has no `swiftc`.
- Design review of the light iOS chrome: selected pane uses only the SPEC board colors; the dark selected pill stays the checked-in white/black pair.
- Review of the light failure row on a real capture. The token board shows the peach composite of `#FFDAD6` at 55% over `#EEF5F2`, with `#410002` text and a `#BA1A1A` stroke. The product PNGs `lv-and-04-term-*` and `lv-ios-04-term` are still the historic dark drawings and were not used as the light palette.
