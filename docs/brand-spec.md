# Luvia brand

Warm Minimal. iOS uses Liquid Glass (iOS 26) with material fallback. Android uses Material You dynamic color, seeded by the same warm accent when dynamic color is unavailable.

## Colors

### Primary accent
- Light: `#C45C26`
- Dark: `#E07A42`
- Usage: primary actions, pairing progress, blocked emphasis

### Neutrals
| Token | Light | Dark | Usage |
| --- | --- | --- | --- |
| Canvas | `#F4F0EA` | `#161412` | Screen background |
| Surface | `#FFFBF6` | `#1E1B18` | Cards, grouped rows |
| Ink | `#1C1916` | `#F3EDE6` | Primary text |
| Ink muted | `#6A635C` | `#A39B93` | Metadata |
| Live | `#2F6F4E` | `#5BA87A` | Connection live |
| Terminal bg | `#1A1815` | `#1A1815` | Terminal pane only |

## Typography
- Display: New York (`Font.system(..., design: .serif)`) for Luvia / No Hosts / pairing titles
- Body: SF Pro
- Mono: SF Mono for fingerprints, install command, terminal
- Android display: `FontFamily.Serif` on headline/title

## Spacing
8pt grid: 4 / 8 / 16 / 24 / 32 / 48

## Radius
8 / 12 / 16 continuous

## Signature
Pairing and empty-host: serif title + one glass (iOS) or tonal FAB (Android) action. No extra decoration.
