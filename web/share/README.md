# Public share page (`web/share/`)

The page a "Share flight" link opens (docs/P1_CONTRACTS.md section 2). Static files, no build step.
Deploy the folder as-is to GitHub Pages, Cloudflare Pages or any static host.

```
{SHARE_BASE_URL}#<token>        e.g. https://heygate.app/f/#q0x3VQb2Tn6c1bq6l8gYw0n5vHk2c3d4e5f6g7h8i9j
```

* The token sits after `#`, so browsers never send it to the page's host, and `Referrer-Policy: no-referrer`
  keeps it out of other requests.
* The page fetches `{SUPABASE_URL}/functions/v1/shared-flight?t=<token>` on load and every 60 seconds
  (paused while the tab is hidden), and redraws the plane every 15 seconds.
* 404 (unknown, revoked or expired link) shows "This link isn't working" and stops polling.

## Files

| File | What |
|---|---|
| `index.html` | Markup and the Content-Security-Policy |
| `config.js` | `SUPABASE_URL` (required), optional `SUPABASE_ANON_KEY`, `REFRESH_SECONDS` |
| `share.js` | Fetch, render and the globe (ES module; its pure helpers are tested in `supabase/functions/tests/share.test.ts`) |
| `share.css` | HeyTurbulence palette (ivory page, forest land, vermilion route), DM Sans + Instrument Serif from Google Fonts, dark mode |
| `vendor/` | Pinned third-party code, vendored so the page needs no JS CDN (licences in `vendor/LICENSES.txt`) |

## Setup

1. Set `SUPABASE_URL` in `config.js` to the project URL. `shared-flight` is deployed with `verify_jwt = false`
   and needs no key.
2. If the API is not on `*.supabase.co` (custom domain), add its origin to `connect-src` in the CSP in
   `index.html`.
3. Deploy the function: `supabase functions deploy shared-flight`.
4. Serve this folder at `SHARE_BASE_URL` (the apps default to `https://heygate.app/f/`).

Local check: `supabase start`, set `SUPABASE_URL` to `http://127.0.0.1:54321` (already allowed by the CSP),
then `python3 -m http.server -d web/share 8080` and open `http://localhost:8080/#<token>`.

## What it shows

Flight number, airline, route, one status word (On time / Delayed N min / Boarding · Gate 42B / In the air /
Landed · Belt 5 / Cancelled / Diverted), departure and arrival times in each airport's own time zone (struck-through
timetable time when it moved 5+ minutes), terminal, gate and belt, and a globe with the great-circle route: dashed
ahead, solid vermilion behind the plane. The plane is drawn at the live position when the provider sent one in the
last 30 minutes, otherwise at a timetable estimate labelled "Plane position estimated from the timetable".

The response carries public fields only (no PNR, seat, user, device or tail number); the page stores nothing,
sets no cookies and loads no analytics.

## Globe

`drawGlobe` in `share.js` is adapted from HeyTurbulence `src/globe.js`: same orthographic projection, sea, graticule,
land and rim colours, airport dots and label chips. Changes: it zooms so a domestic hop fills the card, draws the
flown part of the route, and adds the plane.

## Vendored versions (exact, from npm)

| File | Package | sha384 |
|---|---|---|
| `vendor/d3-array@3.2.4.min.js` | d3-array 3.2.4 (`dist/d3-array.min.js`) | `56928c754f939698201c58a9e3ab49ff190552cf0d141c0ba94328dfedee8aee35d6553a794418b07554f891690398da` |
| `vendor/d3-geo@3.1.1.min.js` | d3-geo 3.1.1 (`dist/d3-geo.min.js`) | `54d88a4545e9d0c9a09457ea0f091fba4f98efc0b759325285d292685c0e69c2d1dd499a9b11c824b0530f705a7485c3` |
| `vendor/topojson-client@3.1.0.min.js` | topojson-client 3.1.0 (`dist/topojson-client.min.js`) | `524bf5a7fc5399ae8fe3fd9b6392b3581c3ec9d4a95e684232dc9c8884150c9d5198ec6d09834c175b974fd4fadc7ebb` |
| `vendor/world-atlas@2.0.2-land-110m.json` | world-atlas 2.0.2 (`land-110m.json`) | `e6814e1a831dd2d91a8185b4f2555ca38b808bb5c310307007139d78ac7e480d6285bb07891fda140246adeb65b93cc6` |

The same files are on jsDelivr at `https://cdn.jsdelivr.net/npm/<package>@<version>/<path>` if you prefer a CDN;
then add `https://cdn.jsdelivr.net` to `script-src` and `connect-src`.
