# ADR 0004: Chat-first native UI

Status: accepted (2026-10-30)

## Context

ADR 0001 keeps the phone off the raw PTY: structured control is primary, and the
terminal is a secondary surface. v2 (`docs/design/product-v2.md`) turned that
into a Now / Projects information architecture with a warm-orange theme. The
phone already has the pieces that matter underneath that chrome: `luvia-host`,
pinned SSH, the shared thread model (`AgentThread`, `TimelineItem`, `Ask`), and
ACP tool calls.

The wow design (structure A) asks for a different surface:

- Pair → session list → chat → fullscreen terminal from the top bar
- Android: Compose and Material You, dynamic color, seed `#006A62`
- iOS: SwiftUI and Liquid Glass
- v1 creates a session from the top-bar `+` only. No extended FAB

Review, Tasks, and the other UHP boards stay in `shared/`. They are not v1
chrome. Trust rules from ADR 0001–0003 do not change: the phone still never
holds Luvus credentials, mutations are not retried, and a lost result is
Unconfirmed.

## Decision

1. Structure A is the phone IA. The primary screen is one conversation. The
   session list is how you pick it. The terminal is still secondary: it opens
   from the top bar, fullscreen, and returns to the conversation.
2. Android UI is Jetpack Compose with Material 3. Dynamic color is on. When
   the platform cannot supply a wallpaper scheme, the fallback seed is
   `#006A62`. Decision cards use the tertiary container so they stay distinct
   from the teal primary.
3. iOS UI is SwiftUI. Liquid Glass (`glassEffect`, `GlassEffectContainer`) is
   used where the OS provides it, with a material fallback on earlier versions.
4. v1 has no extended or floating action button. A new session is the top-bar
   `+`.
5. The 6-digit pairing ritual in the UI is visual until the host grows a short
   code. Pairing still completes with the existing `luvia1:` code or QR. The
   host command stays `luvia-host pair`.
6. Tool trails render ACP tool calls (read / edit / run). Pane transcripts are
   not mined for fake tool rows. That matches the v2 rule “do not invent
   structure,” and it stays in force.
7. `docs/design/product-v2.md` is superseded for IA and visual design. ADR
   0001’s “terminal is secondary” decision still holds. ADR 0001’s list of
   primary tabs (Agents, Review, Tasks, Automations) does not: those boards
   remain in shared code and are not v1 navigation.

## Consequences

- Android `ui/` screens and the iOS Now/Projects views are replaced. Font
  prewarm, the terminal canvas, QR scanning, push, Live Activities, and
  keychain wrappers stay.
- `host/`, `transport/`, `relay/`, and shared protocol semantics stay. Session
  filters and Ask presentation fields may be added beside the thread model;
  they do not change the wire protocol.
- A host short code and pane tool events are follow-up work, not part of this
  UI change.

## Amendment (2026-11-03)

Decision 5's visual six-digit ritual now has a host behind it. `luvia-host pair --short`
(and `pair-code --short`) mints an in-memory one-time ticket, default 90 seconds,
at most five failed redeems, bound to the grant's `dk`. `POST /pair/redeem` on
RFC1918, ULA, link-local, or an advertised Tailscale address returns the same
`luvia1:` payload, and the phone still calls `completePairing`. Pasting or
scanning `luvia1:` stays the fallback. `shortFingerprint` is still only a UI
summary of the device key.

## Note (2026-11-12)

Superseded for pairing and identity by tailnet v3. Pairing codes are `luvia2:`
(`luvia1:` is rejected), and device identity is a signature proof inside
`luvia-host bridge`, not a `--device` argument. The history above is unchanged.
