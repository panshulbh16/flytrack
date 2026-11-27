# Public airport pages (`web/airports/`) and yearly report (`web/report/`)

Static pages, no build step (docs/P3_CONTRACTS.md B8 and B11). They reuse `web/share/`: its `config.js`
(`SUPABASE_URL`), `share.css` (HeyTurbulence palette, fonts, dark mode) and the vendored d3-geo / topojson /
world-atlas files. **Deploy the whole `web/` folder as one site** so the `../share/` paths resolve, e.g.

```
https://heygate.app/share/      web/share     (the apps' SHARE_BASE_URL can stay /f/ with a rewrite to /share/)
https://heygate.app/airports/   web/airports
https://heygate.app/report/     web/report
```

| Page | What | API (public, `verify_jwt = false`, CORS `*`) |
|---|---|---|
| `airports/index.html` | The metros with their live level (Normal / Busy / Disrupted / Severe) | `GET /functions/v1/airport-status?iata=DEL` per metro |
| `airports/airport.html#DEL` | One airport: level, headline, weather, today's departures / departed / % late / cancelled, globe | same, refreshed every 5 min while visible |
| `report/index.html?year=2026` (or `#2026`) | The year in flights: totals, most punctual / delayed airlines and airports, busiest routes, fog days at DEL, Share button | `GET /functions/v1/global-report?year=2026` |

* `airport-status` is cached 5 minutes and `global-report` 1 hour (function memory + `Cache-Control`).
* Neither response ever carries user data: `airport_status_public` returns counts and weather only, and
  `global_report` applies k-anonymity (rankings need 200+ legs, traveller counts are rounded to 100 and hidden
  under 1,000). `supabase/functions/_shared/public-stats.ts` re-applies those rules on the way out.
* The pages store nothing, set no cookies and load no analytics. Same CSP as the share page.
* Pure helpers (`iataFromHash`, `levelInfo`, `todayTiles`, `yearFromLocation`, `formatBig`, `rankRows`, ...) are
  tested in `supabase/functions/tests/public-web.test.ts`.

Deploy the functions: `supabase functions deploy airport-status global-report`.
