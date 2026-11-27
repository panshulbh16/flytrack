# P3 contracts: finish every remaining Flighty feature

This step finishes the 5 partial features and builds the 13 missing ones. Six items stay skipped on purpose: ground and proximity radar, Android Auto/CarPlay, TripIt, gift cards, and full terminal maps (reasons in `docs/FLIGHTY_PARITY.md` §4).

All earlier contracts (P1, P2 wave 1 and 2) still apply.

Migrations are numbered **21–30**, and the old ones are never edited. Every new database item ships with SQL tests, every new function with Deno tests, and every piece of app logic with unit tests.

## A. Partial features → complete

### A1. Unlimited free tracking
* There is no longer a free *product* limit.
* `enforce_tracking_limit` keeps an anti-abuse cap of **100 active flights for free, 300 for Pro** (migration 21).
* The apps show no count-based upsell any more.

### A2. Full timeline
**New `flights` columns (migration 22)**, filled by providers:

| Column | Meaning | AeroDataBox source |
|---|---|---|
| `dep_offblock_at` | pushback | departure `revisedTime` once departed |
| `dep_runway_at` | takeoff | departure `runwayTime` |
| `arr_runway_at` | touchdown | arrival `runwayTime` |
| `arr_onblock_at` | at gate | arrival `revisedTime` when status is Arrived |

* The mock provider fills them as well.
* `act_dep` / `act_arr` keep their meaning, so the earlier logic is unchanged.

**New free events:** `GATE_CLOSED` (status `gate_closed`, info), `TAKEOFF` (runway time known, info), `AT_GATE` (on-block, info).

**RPC `flight_timeline(p_flight_id uuid) returns jsonb`**
* Readable when `can_read_flight` allows it.
* It returns an ordered list `[{ key, at, state: "done"|"current"|"next", detail }]`.
* `key` is one of: `scheduled`, `checkin_open`, `gate_assigned`, `boarding`, `gate_closed`, `pushback`, `takeoff`, `landing`, `at_gate`, `belt`, `cancelled`, `diverted`.
* Built from the flight row, `flight_events` and `flight_status_history`.
* Free users get the milestone keys and times. Pro adds `detail` (e.g. gate numbers, the delay at each step).

### A3. Calendar import (Pro)
* Read-only access to the device calendar: Android `READ_CALENDAR` runtime permission, iOS EventKit full access (asked for only when the user taps Import).
* It scans the next 365 days for flight-like events.
* The parser is a port of HeyTurbulence `src/calendar-parser.js` and `src/calendar.js`: flight-number regexes, airline names and IATA routes in titles, locations and notes. It is ported to Kotlin and Swift with the **same fixtures** in both test suites.
* Results are shown as suggestions. "Add" uses the normal search-and-track path with `source = 'calendar'` (added to the `user_flights.source` check in migration 21).

### A4. iOS widgets
* WidgetKit extension `FlyTrackWidget`, with small, medium and lock-screen (accessoryRectangular / accessoryInline) families.
* It shows the next or current flight. It reads data from an App Group (`group.app.flytrack`) that the app writes on every sync.
* Timeline entries are refreshed at the milestone times.

### A5. Aircraft age + swap alert
**Table `aircraft` (migration 23)**
* Columns: `reg` (pk), `model`, `icao_type`, `first_flight_date`, `delivery_date`, `age_years` numeric, `airline_iata`, `seat_config` text null, `fetched_at`.
* It is filled from AeroDataBox `GET /aircrafts/reg/{reg}`, with a 30-day cache; the mock returns a fixed aircraft.
* It is fetched lazily when a Pro-tracked flight first gets a tail number.

**Event `AIRCRAFT_SWAP`** (Pro, critical)
* Sent when `aircraft_reg` changes on a flight that has not departed.
* Payload: `{ old_reg, new_reg, old_model, new_model }`.

**Display:** `flight_view` and the detail screens show the type for everyone. Pro users also see age ("8.4 years old") and the swap history.

## B. Missing features → build

### B1. Delay prediction (Pro)
**RPC `delay_prediction(p_flight_id) returns jsonb`**

```json
{ "p_delay_15": 0.42, "p_delay_60": 0.08, "expected_delay_min": 17,
  "factors": [{"key":"history","effect_min":9,"label":"This flight is late 45% of the time"},
              {"key":"inbound","effect_min":20,"label":"Your plane is running 20 min late"},
              {"key":"weather","effect_min":15,"label":"Fog at DEL"},
              {"key":"time_of_day","effect_min":4,"label":"Evening departures run later"},
              {"key":"airport","effect_min":6,"label":"DEL departures are averaging 22 min late"}],
  "confidence": "low|medium|high", "model": "rules-v1", "computed_at": "..." }
```

**How it is computed**
* Use empirical Bayes on `flight_leg_history`:
  * Start from a **route** prior (dep→arr, last 90 days), shrunk toward the **carrier + route** history and then the **flight number** history. Weights are by sample count with k = 10.
  * Then adjust for: inbound lateness (`prediction.lateMin`), Airport Health level / fog at departure, and the departure-hour bucket (local time: 05–09, 09–17, 17–22, 22–05).
* Confidence: high when samples ≥ 20, medium when ≥ 8, otherwise low.
* Labels are in en/hi/hinglish, chosen by `p_lang`.

**When it is available:** from 72 h before departure until departure. It is cached in `delay_predictions(flight_id pk, json, computed_at)` and recomputed at most every 15 min.

**Wording:** the UI always says "Prediction". `model` names the method so ML can replace it later.

### B2. Gate prediction (Pro)
**RPC `gate_prediction(p_flight_id) returns jsonb`**
* Shape: `{ "gate": "42B", "terminal": "1", "share": 0.78, "samples": 9, "alternatives": [{"gate":"40","share":0.11}] }`.
* Built from `flight_leg_history` for the same carrier, number and dep airport over the last 60 days.
* It returns null when there are fewer than 4 samples.
* It is shown only while no real gate is assigned, labelled "Usually gate 42B (7 of 9)".

### B3. Taxi times (Pro)
* `flight_leg_history` gains `taxi_out_min` (runway − offblock) and `taxi_in_min` (onblock − runway) in migration 22.
* **RPC `taxi_stats(p_iata text) returns jsonb`**
  * Shape: `{ "taxi_out": {"p50":18,"p80":27,"samples":312}, "taxi_in": {...} }`.
  * Covers the last 30 days.
* Shown on the detail screen as "Typical taxi out at DEL: 18 min".

### B4. Connection assistant (Pro)
**Trips** are derived for each user: a series of their tracked flights where leg n+1 departs from leg n's arrival airport within 24 h. No table is needed.

**RPC `my_connections() returns jsonb`**

```json
[{ "in_flight_id", "out_flight_id", "airport": "DEL",
   "layover_min": 85, "mct_min": 75, "buffer_min": 10,
   "risk": "ok|tight|at_risk|missed",
   "terminal_change": "T1→T3", "transfer_note": "Shuttle between T1 and T3, about 20–30 min" }]
```

**Minimum connection times:** table `min_connection_times(airport, from_terminal null, to_terminal null, dom_dom, dom_intl, intl_dom, intl_intl)`, seeded for DEL, BOM, BLR, HYD, MAA, CCU, COK, AMD.
* Default when there is no row: 45 / 75 / 90 / 60.
* DEL T1↔T3 adds 60 min (shuttle).
* BOM T1↔T2 adds 60 min.
* Mark every seed row `verified = false`.

**Risk levels**

| Risk | Rule (buffer = layover − MCT) |
|---|---|
| `ok` | buffer ≥ 30 |
| `tight` | 0 ≤ buffer < 30 |
| `at_risk` | buffer < 0 |
| `missed` | the out flight has left before the in flight arrived |

**Event `CONNECTION_AT_RISK`** (Pro, critical)
* Sent on the **in** flight when its risk goes from ok/tight to at_risk.
* Payload: `{ out_flight, out_dep, airport, buffer_min }`.
* Dedupe key: `{in}:{out}:CONN:{gen}`.
* It is computed in the poll cycle (DB-only step, `runConnectionAlerts`).

### B5. Search by route or airport (free, rate-limited)
**Edge Function `search-route`** (JWT)
* Body: `{ "dep": "DEL", "arr": "BOM"?, "date": "2026-11-28", "airline": "6E"? }`.
* It returns that day's departures from the airport, filtered by `arr` and `airline`.
* Provider method `getAirportDepartures(iata, fromLocal, toLocal)`: AeroDataBox `GET /flights/airports/iata/{iata}/{from}/{to}`, in 12-hour windows, with codeshares removed.
* Results are cached in `route_search_cache(dep, date, payload, fetched_at)` for 6 h. The cache holds the whole airport-day; `arr` and `airline` are applied when it is read.
* Each call costs **2** units of the search quota.
* Only legs that match `arr` and `airline` are written, and a cache miss does not write them one by one. New legs (and legs nobody tracks) go in as one bulk upsert plus one bulk `flight_status_history` insert — the same columns `ingest` stores for a flight-number search of a flight with no trackers, so there is nothing to alert. A leg somebody already tracks, or that has a Live Activity, still goes through `ingest` (exactly-once delay, gate and cancellation alerts). That ingest does not block the response: the row returned for it already shows the fresh snapshot, and the write finishes in the background.
* Response: `{ "flights": [<search-flight row shape>] }`, so the apps reuse the add-flight result list. Each item is a stored `flights` row (real `id`) the app can track.
* Add-flight gets a "By route" tab with from/to airport pickers (the bundled airports list) and a date.

### B6. Past flight search (free)
* `search-flight` accepts dates up to **365 days back** when `mode = "past"`.
* Past flights are stored with `next_poll_at = null`, so they are never polled. They go into `flight_leg_history` immediately if the provider has actual times.
* Tracking a past flight uses `source = 'manual'` with `archived_at = now()`, so it lands straight in Passport.
* Add-flight gets an "Add a past flight (for Passport)" toggle.

### B7. Airport delay trends (Pro)
**RPC `airport_trends(p_iata, p_days int default 14) returns jsonb`**
* Shape: `{ "by_hour": [{"hour":6,"avg_dep_delay":12,"delayed_pct":28,"samples":40}], "by_day": [{"date":"2026-11-20","avg_dep_delay":19,"cancelled":2}] }`.
* `by_hour` uses airport-local hours.
* Built from `flight_leg_history`.
* Shown as a chart on the Airport Health detail screen.

### B8. Public airport web page (free, SEO)
**Edge Function `airport-status`**
* `GET ?iata=DEL`, public, CORS `*`.
* Returns the **public** part of `get_airport_health` (level, headline, weather summary) plus today's counts (departures, delayed %, cancelled). It never includes any user data.
* Cached for 5 min.

**Static site `web/airports/`**
* `index.html` lists the metros, and `airport.html#DEL` shows one airport.
* Same design and vendored libraries as `web/share/`.

### B9. Watch apps
**Wear OS** (new Gradle module `:wear`, package `app.flytrack.wear`)
* A **Tile** and a **complication** showing the next flight: number, route, status, departure time, gate.
* Data comes from the phone through the Wearable Data Layer: the phone app publishes `/next_flight` (JSON, the compact flight shape) whenever its sync updates.
* Dependencies: `androidx.wear.tiles`, `androidx.wear.protolayout`, `androidx.wear.watchface:watchface-complications-data-source-ktx`, `com.google.android.gms:play-services-wearable`. Pick the latest stable versions from Google Maven, add them to the version catalog, and use them only in `:wear` (and in `:app` for the data layer client).

**Apple Watch**
* A watchOS app target `FlyTrackWatch` in `project.yml`, with a SwiftUI next-flight view and a WidgetKit complication.
* Data comes over WatchConnectivity (`updateApplicationContext` with the compact flight JSON).

### B10. Compare Passports with friends (free)
* `profiles.share_passport_with_friends` (bool, default true, user-editable).
* **RPC `friend_passport(p_friend uuid) returns jsonb`**
  * Returns a reduced passport: flights, distance_km, airports count, countries, airlines top 3, longest. Same year rules as `my_passport`.
  * Only for accepted friends who share their passport. Otherwise error `not_shared` (42501).
* The UI compares side by side: You vs Asha.

### B11. Global yearly report (free, public)
**RPC `global_report(p_year int) returns jsonb`** (anonymous aggregate, service role)
* Totals: flights tracked, distance, unique users. Unique users are bucketed to the nearest 100 and are omitted under 1,000.
* Most delayed / most punctual airlines and airports. A row needs at least 200 legs.
* Busiest routes.
* Fog-delay days at DEL.

**Edge Function `global-report`**
* `GET ?year=2026`, public, cached for 1 h.

**Static page `web/report/`**
* A shareable year report in the HeyTurbulence style.

### B12. Crew mode (free)
* `profiles.crew_mode` (bool).
* When on:
  * Check-in, when-to-leave, boarding and gate-closed alerts are muted.
  * Pro upsells are hidden.
  * The timeline shows block times (off-block to on-block).
  * Passport counts block hours.
* The backend skips CHECKIN_*, BOARDING and when-to-leave for crew users.

### B13. Family sharing (Pro)
**Tables (migration 29)**
* `families(id, owner_id unique, created_at)`.
* `family_members(family_id, user_id unique, joined_at)`, with at most **5 members** besides the owner.

**RPCs**
* `create_family_invite() returns text`: 8-char code like the friend invites. Only an owner who is Pro on a **yearly or lifetime** plan may create one.
* `accept_family_invite(code)`.
* `leave_family()`.
* `remove_family_member(user)`.
* `my_family() returns jsonb`.

**Entitlement:** `is_pro(user)` becomes "own Pro **or** member of a family whose owner `is_pro`".
* A member's Pro is never inherited further.
* Members show "Pro via {owner name}'s family".

## C. Migration numbering

| File | Contents |
|---|---|
| `21_limits_sources.sql` | tracking cap 100/300, `calendar` source |
| `22_timeline.sql` | block-time columns, `GATE_CLOSED` / `TAKEOFF` / `AT_GATE` events, `flight_timeline`, taxi columns, `taxi_stats` |
| `23_aircraft.sql` | `aircraft`, `AIRCRAFT_SWAP` |
| `24_predictions.sql` | `delay_prediction`, `gate_prediction`, `delay_predictions` cache |
| `25_connections.sql` | `min_connection_times`, `my_connections`, `CONNECTION_AT_RISK` |
| `26_route_search.sql` | `route_search_cache` |
| `27_trends_public.sql` | `airport_trends`, airport-status helper, `global_report` |
| `28_social.sql` | `share_passport_with_friends`, `friend_passport`, `crew_mode` |
| `29_family.sql` | families, `is_pro` update |
| `30_event_types.sql` | adds every new event type to the `flight_events` check **once** (GATE_CLOSED, TAKEOFF, AT_GATE, AIRCRAFT_SWAP, CONNECTION_AT_RISK). Owned by backend-intel |

**Event types order:** append to `types.ts` `EVENT_TYPES` in exactly this order: `GATE_CLOSED`, `TAKEOFF`, `AT_GATE`, `AIRCRAFT_SWAP`, `CONNECTION_AT_RISK`. Android `AlertType` and iOS follow the same order.

**Every new event** needs en/hi/hinglish templates and a channel. `AIRCRAFT_SWAP` and `CONNECTION_AT_RISK` are Pro-only.

## D. Ownership

| Area | Owns |
|---|---|
| backend-intel | A2 timeline (backend), A5, B1, B2, B3, B4 backend, migrations 22–25 + 30, provider additions (block times, aircraft, `getAirportDepartures` in the provider interface + mock + AeroDataBox), `notify.ts` templates for the new events |
| backend-search-social | A1, the A3 `calendar` source, B5 / B6 backend (`search-route`, past mode in `search-flight`), B7, B8, B10, B11, B12 backend, B13, migrations 21 and 26–29, `web/airports/`, `web/report/` |
| android-intel | timeline screen, prediction / gate / taxi / trends / aircraft cards, connection assistant screen, new AlertTypes + groups |
| android-search-social | route search tab, past-flight toggle, calendar import (A3), friend Passport compare, crew mode, family screens, removal of the A1 upsell |
| android-wear | the `:wear` module plus the `:app` data-layer publisher |
| ios-intel-widgets | A2 / A5 / B1–B4 UI on iOS, plus the A4 WidgetKit extension |
| ios-search-social-watch | A3 / B5 / B6 / B10 / B12 / B13 on iOS, plus the B9 Apple Watch target |
| i18n | after everything: Hindi for all new Android strings and iOS keys |

## E. Backend-intel implementation notes (for the app areas)

These pin down details the sections above leave open. Names match migrations 22–25 and 30.

* **Errors.** Pro RPCs (`delay_prediction`, `gate_prediction`, `taxi_stats`, `my_connections`) raise `pro_required` (SQLSTATE 42501) for callers without Pro, like `airport_trends`. Flight RPCs raise `not_allowed` (42501) when `can_read_flight` is false, which includes an unknown id (no existence oracle); apps treat both as "not available". `not_found` (P0002) is only a safety net.
* **`flight_timeline`.** Order: `scheduled, checkin_open, gate_assigned, boarding, gate_closed, pushback, takeoff, [diverted], landing, at_gate, belt`. A cancelled flight lists only what happened, then `cancelled` (current). `at` is ISO-8601 UTC (minutes) or null: for `next` items it is the expected time where known (check-in window, departure estimate for `pushback`, arrival estimate for `landing`). Implied milestones (proven by a later one) are `done` with `at: null`; `checkin_open`, `gate_assigned` and `belt` are left out when they never happened. Pro `detail` per key: `scheduled {sched_dep, sched_arr}`, `checkin_open {closes_at}`, `gate_assigned`/`boarding {gate, terminal}`, `gate_closed {gate}`, `pushback {delay_min}`, `takeoff {taxi_out_min}`, `diverted {from_arr, to_arr}`, `landing {delay_min}`, `at_gate {gate, terminal, taxi_in_min}`, `belt {belt}`, `cancelled {sched_dep}`.
* **`delay_prediction(p_flight_id, p_lang default 'en')`** returns null outside the window (more than 72 h ahead, departed, cancelled). The JSON also has `samples` (route legs). `factors[].effect_min` add up to `expected_delay_min` exactly (`history` is first and takes the rounding remainder).
* **`gate_prediction`** also returns `count` (legs at the top gate) so the label "Usually gate 42B (7 of 9)" is `count` of `samples`. Up to 3 alternatives.
* **`taxi_stats(p_iata)`** returns `{iata, window_days: 30, taxi_out: {p50, p80, samples}, taxi_in: {...}}`; p50/p80 are null with 0 samples.
* **`my_connections()`** items also carry `in_flight`, `out_flight` ("6E 512"), `in_arr` and `out_dep` (best known times, ISO). Ordered by `out_dep`; connections whose departing leg left more than 12 h ago are dropped. A `CONNECTION_AT_RISK` row in `flight_events` is visible only to callers who also track its `out_flight_id` (the same users the push goes to, also when the sweeper re-sends it).
* **`flight_view`** gains, at the end: `dep_offblock_at, dep_runway_at, arr_runway_at, arr_onblock_at, aircraft_icao_type` (everyone) and `aircraft_age_years` (Pro for the flight, one decimal). A later migration that redefines `flight_view` must keep these columns.
* **Sync push (`compactFlight`)** gains `ofb` (off-block), `rwd` (takeoff), `rwa` (touchdown), `onb` (on-block).
* **Event payloads.** `GATE_CLOSED {gate, terminal, estDep}`, `TAKEOFF {runwayAt, estArr}`, `AT_GATE {onblockAt, arrGate, arrTerminal, belt}`, `AIRCRAFT_SWAP {old_reg, new_reg, old_model, new_model}`, `CONNECTION_AT_RISK {out_flight, out_dep, airport, buffer_min, layover_min, mct_min, out_flight_id}`; all also carry `flight, dep, arr`. Push tags: `GATE_CLOSED` replaces the boarding notification, `TAKEOFF` replaces "departed", the others have their own tag. `AIRCRAFT_SWAP` and `CONNECTION_AT_RISK` use `flight_critical`, the three milestones `flight_updates`.
