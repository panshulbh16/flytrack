# UI design brief — match HeyTurbulence (by Roamly)

Source: built CSS/JS of github.com/panshulbh16/heyturbulence-site (heyturbulence.com). Read from code, not screenshots.
HeyGate and HeyTurbulence should look like one family: same paper, forest-green ink, terracotta accent and fonts.
HeyGate tried "Aubergine and platinum" and then "Midnight navy and platinum" and came back to this palette at the user's request.
Code: iOS `ios/FlyTrack/Theme.swift` (`FlyColor`, each token adapts to light/dark), Android `ui/theme/Color.kt`.
Screenshots in `docs/screenshots/` still show the earlier aubergine palette; retake them.

## Look & feel
- Editorial "atlas" style: warm paper background, deep forest-green ink, terracotta accent, 1px hairlines instead of heavy cards.
- Light theme is primary; dark is HeyTurbulence's "Night" share-card palette (deep forest surfaces, warm off-white text, sage actions).
- **Appearance setting**: Settings → Appearance offers System (default), Light and Dark on both apps.
  iOS stores it in `@AppStorage("appearance")` and applies `.preferredColorScheme` at the root; Android stores it in
  DataStore (`SettingsStore.appearance`) and passes it to `FlyTrackTheme(darkTheme)`, with system-bar icons following it.
- Terracotta has two strengths: the brand terracotta (#C86842) for rings, rules, route arrows and other decoration (3.5:1 on paper, so
  never small text), and a darker terracotta (#A34E2E, 5.2:1) whenever it is small text. Dark uses #DF754E for decoration and #E88A62 for text.
- Fonts (Google Fonts, OFL): **Instrument Serif** (display, italics for emphasis) + **DM Sans** (UI). Tabular numerals everywhere.
- Icons: line icons, 24dp grid, 1.6 stroke, round caps. Minimal illustration (tilted plane in an orbit ellipse).
- Motion: ease-out cubic-bezier(.22,1,.36,1), spring (.32,.72,0,1); sheets slide up 40px; scrim #142B24 @40% + blur.
- Map (not built yet): ocean #E2E9DF, land #3E6B56, graticule #C2D0C1, routes = orange great-circle arcs (#DF754E) that draw in,
  airports = cream dots with orange ring.
- iOS toolbar: Settings is a gear icon (label kept for VoiceOver); Add flight is a prominent capsule in the primary colour.
- Launcher icon background and the Android notification accent are the brand forest green (#244735, `@color/forest`).

## Color tokens (Material 3 roles; iOS `FlyColor` uses the same values)
| Role | Light | Dark |
|---|---|---|
| primary / onPrimary (iOS `primary`, the tint; iOS draws `paper` on it) | #244735 / #FFFFFF | #A0B99F / #173F2A |
| primaryContainer / on | #D9DFC8 / #244735 | #35533E / #D9DFC8 |
| secondary (iOS `muted`) | #5A6C5A | #C0CDBB |
| secondaryContainer / on | #E9EDE3 / #244735 | #253E36 / #E9EDE3 |
| tertiary = brand terracotta (iOS `accentLine`) | #C86842 | #DF754E |
| accent text (Android `accentText`, iOS `accent`) | #A34E2E | #E88A62 |
| tertiaryContainer / on | #FBF3EA / #8B422A | #5A2E1E / #FBF3EA |
| error / errorContainer / on | #A34831 / #F5E6D9 / #7A3220 | #F2BDA3 / #6E2E1E / #F5E6D9 |
| background = surface (iOS `paper`) / onSurface (iOS `ink`) | #F6F5EF / #244735 | #182E25 / #F6F4E7 |
| onSurfaceVariant | #5A6C5A | #C0CDBB |
| surfaceContainerLowest…Highest | #FFFDF6, #FBFAF5, #F3F2EA, #EAEEE1, #E4E8DB | #142B24, #1B3329, #1F3A2F, #253E36, #2C473D |
| raised rows and cards (iOS `paperRaised`) | #FBFAF5 | #1F3A2F |
| outline / outlineVariant | #768775 / #D6DCCF | #7A927E / #4C6450 |
| scrim | #142B24 | #142B24 |

Flight status (text / pill container / pill text): on-time #4C7B41 / #DFE7D9 / #2F5A28 · delayed #8D542D / #EFD9A8 / #6B3D1C ·
cancelled #A34831 / #F2BDA3 / #6E2A18 · boarding/in-air = terracotta #A34E2E / #FBF3EA / #8B422A ·
"Estimated (offline)" = outline style + italic serif label in #5A6C5A.
Dark: on-time #A9CFA0 / #2E4A2B / #DFE7D9 · delayed #E8C98A / #4A3518 / #EFD9A8 · cancelled #F2BDA3 / #5C2618 / #F5D3C2 ·
active #E88A62 / #4A2516 / #FBF3EA · estimated #C0CDBB.
The status word always names the state, so colour is never the only cue.

Contrast (WCAG AA, checked): ink on paper 9.5:1 light / 13.1:1 dark · muted 5.2 (≥4.5 on every surface, dialogs included) / 8.7 · accent text 5.2 light / 5.7 dark (4.8 on dark cards) ·
onPrimary on primary 10.4 / 5.6 (iOS paper on primary 9.5 / 6.8) · status words ≥4.6 light, ≥4.8 dark · every status pill text ≥6.3 ·
outline ≥3.0 on every surface in both themes, dialogs included (HeyTurbulence's own #BEC9B9 is 1.6:1, so it was darkened).
The brand terracotta #C86842 (3.5:1) and the outlineVariant hairlines (#D6DCCF) are decorative only.

## Type scale
Serif (Instrument Serif 400): display 57/45/36, headline 32/28/24 (status words like *Delayed*, *On time*).
Sans (DM Sans): title 22/17/14 (500), body 16/14/12, label 14/12/11 (11 = uppercase +1.2 tracking captions).
Custom "numeral": DM Sans 400, 36–40sp, −1 tracking, tnum — departure/arrival times (min Android size 11sp; web's 8–10px is too small).

## Shapes
4dp (xs), 6dp (buttons/inputs), 10dp (cards), 14dp (large), 16dp sheet top, full for chips.

## Patterns to reuse
- Flight row: 44dp circle with a 1dp terracotta ring (`tertiary`, iOS `accentLine`) around the plane icon · route code "DEL → BOM" + "Delhi to Mumbai" · times · duration.
- "Next takeoff · in 3 days" countdown card with a terracotta uppercase caption.
- Plain-language status ("Running 25 min late") instead of raw codes; italic serif for the key word.
- Share cards (light + night). "Little numbers" chips (hours in the sky, km flown).
