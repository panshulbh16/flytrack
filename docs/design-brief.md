# UI design brief — "Aubergine and champagne"

A luxury-getaway palette: deep plum ink on warm ivory, champagne-gold accents, and a plum-black night mode
where gold becomes the action colour. It replaced the earlier HeyTurbulence/Roamly match (forest green + terracotta);
type, shapes and patterns below are unchanged from that brief.
Code: iOS `ios/FlyTrack/Theme.swift` (`FlyColor`, each token adapts to light/dark), Android `ui/theme/Color.kt`.
Screenshots: [iOS, light and dark](screenshots/ios-aubergine-champagne.png) · [Android, light and dark](screenshots/android-aubergine-champagne.png).

## Look & feel
- Editorial "atlas" style: ivory paper, plum ink, champagne gold used sparingly (captions, rings, active state), 1px hairlines instead of heavy cards.
- Light theme is primary. Dark is its own composition, not an inversion: plum-black surfaces, ivory text, gold actions.
- Gold has two strengths: decorative champagne (#C4A26A) for rings and rules, never text (2.1:1 on ivory), and antique gold (#7E5F22) whenever gold is text.
- Fonts (Google Fonts, OFL): **Instrument Serif** (display, italics for emphasis) + **DM Sans** (UI). Tabular numerals everywhere.
- Icons: line icons, 24dp grid, 1.6 stroke, round caps. Minimal illustration (tilted plane in an orbit ellipse).
- Motion: ease-out cubic-bezier(.22,1,.36,1), spring (.32,.72,0,1); sheets slide up 40px; scrim #1C1220 @40% + blur.
- Map (not built yet): ocean #EFE6DA, land #4B2347, graticule #E4D8C6, routes = champagne great-circle arcs (#C4A26A) that draw in,
  airports = ivory dots with gold ring.
- iOS toolbar: Settings is a gear icon (label kept for VoiceOver); Add flight is a prominent capsule in the primary colour.

## Color tokens (Material 3 roles; iOS `FlyColor` uses the same values)
| Role | Light | Dark |
|---|---|---|
| primary / onPrimary (iOS `primary`, the tint) | #4B2347 / #F7F1E8 | #D9BC85 / #1C1220 |
| primaryContainer / on | #EFE1EA / #3A1D38 | #4B2347 / #F3E7CF |
| secondary (iOS `muted`) | #6B5468 | #BFAEBB |
| secondaryContainer / on | #EEE4E6 / #3A1D38 | #3D2C40 / #EFE6E9 |
| tertiary = gold as text (iOS `gold`) | #7E5F22 | #E3C995 (iOS #D9BC85) |
| tertiaryContainer / on | #F3E7CF / #5C4416 | #4A3A1E / #F3E7CF |
| error / errorContainer / on | #9E2B3B / #F6DFE2 / #6E1A26 | #F0A2A9 / #6B2430 / #F6DFE2 |
| background = surface (iOS `paper`) / onSurface (iOS `ink`) | #F7F1E8 / #3A1D38 | #1C1220 / #F3EBDD |
| onSurfaceVariant | #6B5468 | #BFAEBB |
| surfaceContainerLowest…Highest | #FFFDF9, #FFFBF4, #F2EADF, #EFE6DA, #E9DFD2 | #170E1A, #221626, #271A2B, #2F2133, #382A3C |
| raised rows and cards (iOS `paperRaised`) | #FFFBF4 | #271A2B |
| outline / outlineVariant | #988390 / #E4D8C6 | #7A6679 / #3D2C40 |
| decorative gold (iOS `goldLeaf`) | #C4A26A | #B89C68 |
| scrim | #1C1220 | #0E0810 |

Flight status (text / pill container / pill text): on-time #2E6A4C / #E3EFE7 / #1F4D36 · delayed #8A5512 / #F5E6CF / #6A400B ·
cancelled #9E2B3B / #F6DFE2 / #741E2A · boarding/in-air = antique gold #7E5F22 / #F3E7CF / #5C4416 ·
"Estimated (offline)" = outline style + italic serif label in #6B5468.
Dark: on-time #8FCBA8 / #22402F / #D6EEDF · delayed #E7BE7A / #45321A / #F5E6CF · cancelled #F0A2A9 / #4A2128 / #F8D9DC ·
active #D9BC85 / #45361C / #F3E7CF · estimated #BFAEBB.

Contrast (WCAG AA, checked): ink on paper 13.2:1 light / 15.3:1 dark · muted 6.0 / 8.6 · antique gold 5.3 · status words ≥5.5 light, ≥9.0 dark ·
onPrimary on primary 11.5 / 9.9 · every status pill text ≥7.2 · outline ≥3.1 on paper and cards (2.9 on surfaceContainer, so avoid outlines there).
Not yet recoloured: the launcher/app icons (still forest green) until the artwork is redrawn.

## Type scale
Serif (Instrument Serif 400): display 57/45/36, headline 32/28/24 (status words like *Delayed*, *On time*).
Sans (DM Sans): title 22/17/14 (500), body 16/14/12, label 14/12/11 (11 = uppercase +1.2 tracking captions).
Custom "numeral": DM Sans 400, 36–40sp, −1 tracking, tnum — departure/arrival times (min Android size 11sp; web's 8–10px is too small).

## Shapes
4dp (xs), 6dp (buttons/inputs), 10dp (cards), 14dp (large), 16dp sheet top, full for chips.

## Patterns to reuse
- Flight row: 44dp outlined circle with plane icon · route code "DEL → BOM" + "Delhi to Mumbai" · times · duration.
- "Next takeoff · in 3 days" countdown card with orange uppercase caption.
- Plain-language status ("Running 25 min late") instead of raw codes; italic serif for the key word.
- Share cards (light + night). "Little numbers" chips (hours in the sky, km flown).
