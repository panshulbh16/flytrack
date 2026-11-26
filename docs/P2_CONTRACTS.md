# P2 contracts, wave 1 (backend ⇄ Android ⇄ iOS)

These names are fixed for wave 1. All three sides must match. If one changes, edit this file first.

P1 contracts (`docs/P1_CONTRACTS.md`) still apply.

Migrations take numbers 13–16 (section 7). Never edit a migration that already exists.

## 1. Airport Health (free summary, Pro detail)

**Weather data**
* Source: aviationweather.gov METAR JSON, `https://aviationweather.gov/api/data/metar?ids=VIDP,VABB&format=json`.
* It is US public data and free, so no key is needed.
* The User-Agent is `FlyTrack/1.0`. It never includes user data.

**Table `airport_weather`**
* Service-role only.
* Columns:
  * `iata` (pk), `icao`
  * `observed_at`, `fetched_at`
  * `raw_metar`
  * `visibility_m` (int; "CAVOK", "10SM" and similar are normalised to metres, and 9999 means 10 km+)
  * `ceiling_ft` (int, null)
  * `wind_kts`, `gust_kts`, `wx` (present-weather string, e.g. "FG", "BR", "HZ", "TSRA")
  * `flight_category` (`VFR`|`MVFR`|`IFR`|`LIFR`)
  * `fog` (bool: `FG` in wx, or visibility < 1000 m)

**Edge Function `refresh-airport-weather`**
* Protected by `x-cron-secret`, like `poll-flights`.
* pg_cron runs it every 15 min.
* It fetches the metros DEL BOM BLR HYD MAA CCU COK AMD PNQ GOI GOX LKO ATQ JAI, plus every airport with a tracked flight departing or arriving in the next 24 h.
* It makes at most one batched request per 30 ICAO codes.

**RPC `get_airport_health(p_iata text) returns jsonb`**
* Authenticated users.
* It is computed from `airport_weather` plus **our own** `flights` rows at that airport (departures in the last 3 h):

```json
{ "iata": "DEL", "level": "normal|busy|disrupted|severe", "headline": "Fog: visibility 300 m",
  "updated_at": "...",
  "detail": {               // null for non-Pro callers
    "avg_dep_delay_min": 34, "delayed_pct": 41, "cancelled_today": 3, "sample_size": 58,
    "weather": { "visibility_m": 300, "category": "LIFR", "fog": true, "wind_kts": 4, "wx": "FG", "observed_at": "..." } } }
```

**Levels**

| Level | When |
|---|---|
| `severe` | fog with visibility < 550 m, or cancelled ≥ 10 today |
| `disrupted` | visibility < 1500 m or category LIFR (IFR alone is not enough: North Indian winter haze is routinely IFR), or average delay ≥ 45 min |
| `busy` | average delay ≥ 20 min or delayed ≥ 30 % |
| `normal` | otherwise |

Delay rules apply only when the sample has at least 8 flights.

**`headline`** is a short English line. The apps localise it from `level` plus the weather fields; `headline` is the fallback.

**Event `FOG_ALERT`**
* Severity critical, free, mutable with `notify_prefs.FOG_ALERT`.
* Sent once per flight per airport when a tracked, not-yet-departed flight's departure airport (within 4 h of departure) or not-yet-arrived arrival airport (within 4 h of arrival) has `fog` = true or visibility < 1000 m.
* Dedupe key: `{flight}:FOG:{iata}:{local date}`.
* Payload: `{ airport, visibility_m, wx, side: "dep"|"arr" }`.
* Text: "Fog at Delhi: visibility 300 m. Delays likely for 6E 2341."

## 2. Passport and Year in Review

**RPC `my_passport(p_year int default null) returns jsonb`**
* Authenticated users, own data only.
* It counts the caller's `user_flights` (archived included, `source` any) whose flight has departed (`act_dep` not null) or has `sched_dep` in the past and is not cancelled.
* Free users get only the last 12 months, unless `p_year` = the current year. Year in Review is always free.
* `p_year` null means all time (for Pro), or the last 12 months (for free).

```json
{ "scope": "all|last12|year", "year": 2026, "pro_limited": false,
  "flights": 14, "distance_km": 18234, "airborne_minutes": 1810,
  "airports": [{"iata":"DEL","count":9}], "countries": ["IN","AE"],
  "airlines": [{"carrier":"6E","name":"IndiGo","count":8}],
  "aircraft": [{"model":"A320neo","count":7}],
  "longest": {"flight_id":"...","carrier":"AI","number":"101","dep":"DEL","arr":"JFK","distance_km":11752},
  "top_route": {"dep":"DEL","arr":"BOM","count":5},
  "delay": {"total_min": 412, "on_time_pct": 64, "worst": {"carrier":"SG","number":"8","dep_delay_min":185}},
  "co2_kg": 1730, "co2_method": "estimate-v1",
  "by_month": [{"month":"2026-01","flights":2}], "first_flight_date": "2025-12-03" }
```

**Rules**
* Distance is great-circle (haversine on `airports` lat/lon), in km.
* Airborne minutes come from actual times when both exist, otherwise scheduled block time × 0.9.
* On time means departure delay ≤ 15 min (actual vs scheduled).
* `co2_kg` per passenger:
  * distance < 1500 km: `km × 0.115`
  * 1500–4000 km: `km × 0.095`
  * over 4000 km: `km × 0.085`
  * Economy is assumed. It is labelled an estimate. `co2_method` names the method so it can be swapped for the Google Travel Impact Model later.

## 3. Alert extras

**Quiet hours**
* New `profiles` columns:
  * `quiet_hours_enabled` (bool, default false)
  * `quiet_start` (time, default 23:00)
  * `quiet_end` (time, default 07:00)
  * `quiet_tz` (text, IANA, default Asia/Kolkata)
* Users can update them (column grants).
* During quiet hours, **info** alerts are sent silently:
  * Android: FCM notification on channel `flight_quiet`, low importance, no sound.
  * iOS: `interruption-level: passive`, no sound.
* **Critical** alerts are always loud.
* Sync messages are unchanged.

**`DIVERTED_DEST`**
* Severity critical.
* Sent once, after `DIVERTED`, when the diversion airport first becomes known.
* Payload: `{ origArr, newArr, estArr }`.

**Delivery receipts**
* Edge Function `ack-push`: POST, user JWT, body `{ "event_id": 123, "received_at": iso }`.
* It sets `push_log.delivered_at` on the caller's rows for that event, where `delivered_at` is the lesser of `received_at` and `now()`.
* The apps call it when an alert arrives (Android `onMessageReceived` / notification shown, iOS notification service or app delegate), or when it is opened, whichever happens first. Failures are ignored.
* View `push_latency_daily`: service-role only, p50/p90 of `delivered_at - flight_events.created_at`.

## 4. Language (Hindi / Hinglish)

**`profiles.alert_language`**
* Text, check in (`en`, `hi`, `hinglish`), default `en`.
* Users can update it.
* The apps set it from Settings → Language for alerts. The default is the app language: `hi` if the device language is Hindi, else `en`.

**Backend (`notify.ts`)**
* `renderEvent(e, tz, lang)` has templates for every event type in all 3 languages.
* `hi` uses Devanagari; flight numbers, IATA codes and times stay in Latin digits.
* `hinglish` is Roman Hindi, e.g. "Gate change: 6E 2341 ab Gate 29 se. Pehle gate 12 tha."
* `get_flight_recipients` returns `alert_language` and the quiet-hours fields.

**Apps**
* Android `values-hi` and iOS `hi.lproj` are a full Hindi translation (wave 1b).
* "Hinglish" exists only for alerts; the app UI is English or Hindi.

## 5. Map and offline estimate

**Shared estimate algorithm** (identical in Kotlin and Swift, with unit tests on the same fixtures):

* `estimatePosition(dep(lat,lon), arr(lat,lon), depTime, arrTime, now)` returns `{lat, lon, progress 0..1, phase}`.
  * `depTime` = act_dep ?? est_dep ?? sched_dep; `arrTime` = est_arr ?? sched_arr.
  * The position is great-circle (slerp) interpolation along the route.
  * Progress is clamped to 0..1.
  * `phase`: `pre_departure` | `airborne` | `arrived`.
* When a live position is fresher than 20 min, use it and label it **"Live"**. Otherwise use the estimate and label it **"Estimated"**.
* The flight card and detail screen show the progress bar and remaining time from the same estimate when offline.

**Android map**
* MapLibre (already a dependency), with **no tile server**.
* The style is built in code from a bundled `assets/map/land-110m.geojson`, converted from `web/share/vendor/world-atlas@2.0.2-land-110m.json` (public domain, Natural Earth). It works in airplane mode.
* Colours from `docs/design-brief.md`: ocean #E2E9DF, land #3E6B56 (dark-mode equivalents from the brief), and a graticule.
* Route: great-circle line in #DF754E. The flown part is solid and the rest dashed.
* Airports: cream dots with an orange ring.
* Plane: an icon rotated to the bearing.

**iOS map**
* MapKit with `.mutedStandard`, the same overlay colours, and a geodesic polyline (`MKGeodesicPolyline`).
* The offline fallback is the estimate plus the route only.

## 6. Ownership (wave 1)

| Area | Owns |
|---|---|
| backend-health-passport | migrations 13 and 14; functions `refresh-airport-weather`; `_shared/weather.ts`, `_shared/health.ts`, `_shared/passport.ts`; the fog step `runFogAlerts` in engine (it emits events of type `FOG_ALERT`); cron schedule in migration 13; tests |
| backend-alerts-language | migrations 15 and 16; `notify.ts` (templates, quiet hours, language); `types.ts` (FOG_ALERT, DIVERTED_DEST); `diff.ts` (DIVERTED_DEST); `get_flight_recipients`; `ack-push` function; push latency view; tests |
| android-map-offline | `feature/map/**`, `core/estimate/**`, assets/map; flight detail map entry; card/detail progress from the estimate |
| android-passport-health-settings | `feature/passport/**`, `feature/airporthealth/**`, settings rows (quiet hours, alert language), `ack-push` call in push handling; AlertType and AlertGroup additions (FOG_ALERT → a new "Weather" group, DIVERTED_DEST → DISRUPTION) |
| ios-p2 | everything iOS for sections 1–5 |

## 7. Migration numbering

| File | Contents |
|---|---|
| `20261126000013_airport_weather.sql` | weather table, health RPC, FOG_ALERT type, cron job |
| `20261126000014_passport.sql` | `my_passport` |
| `20261126000015_alert_prefs.sql` | quiet hours, `alert_language`, DIVERTED_DEST type, `get_flight_recipients` |
| `20261126000016_push_receipts.sql` | `push_log.delivered_at`, latency view |

Add `FOG_ALERT` and `DIVERTED_DEST` to the `flight_events` type check **once**, in migration 15 (which runs after 13). Migration 13 must not touch that constraint. Migration 13 holds the fog *data*, and the backend-alerts-language area adds the event type and its texts.
