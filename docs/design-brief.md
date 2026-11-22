# UI design brief — match HeyTurbulence (by Roamly)

Source: built CSS/JS of github.com/panshulbh16/heyturbulence-site (heyturbulence.com). Read from code, not screenshots.
Used in Step 3 (Compose theme). Same family feel, adapted for a native Android flight tracker.

## Look & feel
- Editorial "atlas" style: warm paper background, deep forest-green ink, terracotta accent, 1px hairlines instead of heavy cards.
- Light theme is primary; dark theme = the "Night" share-card palette.
- Fonts (Google Fonts, OFL): **Instrument Serif** (display, italics for emphasis) + **DM Sans** (UI). Tabular numerals everywhere.
- Icons: line icons, 24dp grid, 1.6 stroke, round caps. Minimal illustration (tilted plane in an orbit ellipse).
- Motion: ease-out cubic-bezier(.22,1,.36,1), spring (.32,.72,0,1); sheets slide up 40px; scrim #142B24 @40% + blur.
- Map: ocean #E2E9DF, land #3E6B56, graticule #C2D0C1, routes = orange great-circle arcs (#DF754E) that draw in,
  airports = cream dots with orange ring. (MapLibre style JSON will be built with these colors.)

## Color tokens (Material 3 roles)
| Role | Light | Dark |
|---|---|---|
| primary / onPrimary | #244735 / #FFFFFF | #A0B99F / #173F2A |
| primaryContainer | #D9DFC8 | #35533E |
| secondary | #607260 | #C0CDBB |
| secondaryContainer | #E9EDE3 | #253E36 |
| tertiary (accent) | #C86842 | #DF754E |
| tertiaryContainer / on | #FBF3EA / #8B422A | — |
| error / errorContainer | #A34831 / #F5E6D9 | — |
| background = surface / onSurface | #F6F5EF / #244735 | #182E25 / #F6F4E7 |
| onSurfaceVariant | #607260 | #C0CDBB |
| surfaceContainerLowest…Highest | #FFFDF6, #FBFAF5, #F3F2EA, #EAEEE1, #E4E8DB | #1F3A2F, #253E36 |
| outline / outlineVariant | #BEC9B9 / #D6DCCF | #647C68 / #4C6450 |
| scrim | #142B24 | |

Flight status (extended, our addition): on-time #4C7B41/#DFE7D9 · delayed #8D542D/#EFD9A8 ·
cancelled #A34831/#F2BDA3 · boarding/in-air #C86842/#FBF3EA · "Estimated (offline)" = outline style + italic serif label.

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
