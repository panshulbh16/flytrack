# UI design brief — "Aubergine and platinum"

A luxury-getaway palette: deep plum ink on warm ivory, platinum accents, and a plum-black night mode
where platinum becomes the action colour. It replaced the earlier HeyTurbulence/Roamly match (forest green + terracotta);
platinum replaced a first-round champagne gold at the user's request. Type, shapes and patterns below are unchanged.
Code: iOS `ios/FlyTrack/Theme.swift` (`FlyColor`, each token adapts to light/dark), Android `ui/theme/Color.kt`.
Screenshots: [iOS, light and dark](screenshots/ios-aubergine-platinum.png) · [Android, light and dark](screenshots/android-aubergine-platinum.png).

## Look & feel
- Editorial "atlas" style: ivory paper, plum ink, platinum used sparingly (captions, rings, active state), 1px hairlines instead of heavy cards.
- Light theme is primary. Dark is its own composition, not an inversion: plum-black surfaces, ivory text, platinum actions.
- **Appearance setting**: Settings → Appearance offers System (default), Light and Dark on both apps.
  iOS stores it in `@AppStorage("appearance")` and applies `.preferredColorScheme` at the root; Android stores it in
  DataStore (`SettingsStore.appearance`) and passes it to `FlyTrackTheme(darkTheme)`, with system-bar icons following it.
- Platinum has two strengths: decorative platinum (#B4B9C2) for rings and rules, never text (1.9:1 on ivory), and dark platinum (#555B66) whenever it is text.
- Fonts (Google Fonts, OFL): **Instrument Serif** (display, italics for emphasis) + **DM Sans** (UI). Tabular numerals everywhere.
- Icons: line icons, 24dp grid, 1.6 stroke, round caps. Minimal illustration (tilted plane in an orbit ellipse).
- Motion: ease-out cubic-bezier(.22,1,.36,1), spring (.32,.72,0,1); sheets slide up 40px; scrim #1C1220 @40% + blur.
- Map (not built yet): ocean #EFE6DA, land #4B2347, graticule #E4D8C6, routes = platinum great-circle arcs (#B4B9C2) that draw in,
  airports = ivory dots with a platinum ring.
- iOS toolbar: Settings is a gear icon (label kept for VoiceOver); Add flight is a prominent capsule in the primary colour.

## Color tokens (Material 3 roles; iOS `FlyColor` uses the same values)
| Role | Light | Dark |
|---|---|---|
| primary / onPrimary (iOS `primary`, the tint) | #4B2347 / #F7F1E8 | #D3D7DE / #1C1220 |
| primaryContainer / on | #EFE1EA / #3A1D38 | #4B2347 / #F3EBDD |
| secondary (iOS `muted`) | #6B5468 | #BFAEBB |
| secondaryContainer / on | #EEE4E6 / #3A1D38 | #3D2C40 / #EFE6E9 |
| tertiary = platinum as text (iOS `accent`) | #555B66 | #DDE0E6 (iOS #D3D7DE) |
| tertiaryContainer / on | #E6E8EC / #3A3F48 | #3A3E46 / #E6E8EC |
| error / errorContainer / on | #9E2B3B / #F6DFE2 / #6E1A26 | #F0A2A9 / #6B2430 / #F6DFE2 |
| background = surface (iOS `paper`) / onSurface (iOS `ink`) | #F7F1E8 / #3A1D38 | #1C1220 / #F3EBDD |
| onSurfaceVariant | #6B5468 | #BFAEBB |
| surfaceContainerLowest…Highest | #FFFDF9, #FFFBF4, #F2EADF, #EFE6DA, #E9DFD2 | #170E1A, #221626, #271A2B, #2F2133, #382A3C |
| raised rows and cards (iOS `paperRaised`) | #FFFBF4 | #271A2B |
| outline / outlineVariant | #988390 / #E4D8C6 | #7A6679 / #3D2C40 |
| decorative platinum (iOS `accentLine`) | #B4B9C2 | #9AA0AA |
| scrim | #1C1220 | #0E0810 |

Flight status (text / pill container / pill text): on-time #2E6A4C / #E3EFE7 / #1F4D36 · delayed #8A5512 / #F5E6CF / #6A400B ·
cancelled #9E2B3B / #F6DFE2 / #741E2A · boarding/in-air = dark platinum #555B66 / #E6E8EC / #3A3F48 ·
"Estimated (offline)" = outline style + italic serif label in #6B5468.
Dark: on-time #8FCBA8 / #22402F / #D6EEDF · delayed #E7BE7A / #45321A / #F5E6CF · cancelled #F0A2A9 / #4A2128 / #F8D9DC ·
active #D3D7DE / #3A3E46 / #EEF0F3 · estimated #BFAEBB.
Active (platinum) and estimated (mauve) are both quiet greys; the status word always names the state, so colour is never the only cue.

Contrast (WCAG AA, checked): ink on paper 13.2:1 light / 15.3:1 dark · muted 6.0 / 8.6 · platinum text 6.1 light / 12.6 dark · status words ≥5.5 light, ≥9.0 dark ·
onPrimary on primary 11.5 / 12.6 · every status pill text ≥7.2 · outline ≥3.1 on paper and cards (2.9 on surfaceContainer, so avoid outlines there).
Not yet recoloured: the launcher/app icons (still forest green) until the artwork is redrawn.

## Type scale
Serif (Instrument Serif 400): display 57/45/36, headline 32/28/24 (status words like *Delayed*, *On time*).
Sans (DM Sans): title 22/17/14 (500), body 16/14/12, label 14/12/11 (11 = uppercase +1.2 tracking captions).
Custom "numeral": DM Sans 400, 36–40sp, −1 tracking, tnum — departure/arrival times (min Android size 11sp; web's 8–10px is too small).

## Shapes
4dp (xs), 6dp (buttons/inputs), 10dp (cards), 14dp (large), 16dp sheet top, full for chips.

## Patterns to reuse
- Flight row: 44dp outlined circle with plane icon · route code "DEL → BOM" + "Delhi to Mumbai" · times · duration.
- "Next takeoff · in 3 days" countdown card with a platinum uppercase caption.
- Plain-language status ("Running 25 min late") instead of raw codes; italic serif for the key word.
- Share cards (light + night). "Little numbers" chips (hours in the sky, km flown).
