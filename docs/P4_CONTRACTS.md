# P4 contracts: the last Flighty features

P4 builds four features: radar, TripIt sync, Gift Pro and terminal maps. Android Auto and CarPlay stay out: both stores only accept certain app types (navigation, media, messaging, parking/charging, weather) and would reject a flight tracker. Alerts already reach the car screen through the phone.

Migrations are **31–34**. Event types and earlier contracts are unchanged unless listed here.

## 1. Radar (Pro)

There are two modes:
* **Ground radar:** aircraft on the ground within 5 km of an airport, filtered on `on_ground`.
* **Proximity radar:** aircraft within 75 km of the user's coarse location, or of their flight's live/estimated position.

**Feed interface (`_shared/positions.ts`)**
* `interface PositionFeed { name; nearby(lat, lon, radiusKm): Promise<AircraftPosition[]> }`
* `AircraftPosition = { icao24, callsign, reg?, lat, lon, alt_ft, gs_kts, track_deg, on_ground, vrate_fpm?, seen_at }`

**Adapters**, chosen by the secret `RADAR_FEED`:

| Value | Adapter | Notes |
|---|---|---|
| `none` (default) | returns `feed_unavailable` | |
| `opensky` | OpenSky REST `/api/states/all?lamin..lomax`, OAuth client credentials | |
| `adsbx` | ADS-B Exchange REST `/v2/lat/{lat}/lon/{lon}/dist/{nm}/`, header `api-auth` | |

* Any feed other than `none` also needs the secret `RADAR_FEED_LICENSED=true`, set by the owner once a commercial licence is in place. Without it, the function returns `{ "error": "feed_unlicensed" }`, so a free non-commercial feed is never used in a paid app by accident.

**Edge Function `radar`**
* JWT; Pro only (`current_user_is_pro()`).
* Request: `GET ?mode=ground&iata=DEL` or `GET ?mode=proximity&lat=28.6&lon=77.2`.
* Coordinates are rounded to 0.05° on the server, both for privacy and for the cache key.
* Results are cached in memory for 10 s per (mode, cell).
* Rate limit: 60 requests per user per 10 minutes, kept in a small table `radar_quota` (migration 31).
* Response: `{ "center": {lat,lon}, "radius_km": 5, "aircraft": [AircraftPosition & { "flight": "6E2341"? }], "feed": "opensky", "as_of": "..." }`. The `flight` field is filled by matching the callsign to our `flights` rows when possible.
* Errors: `pro_required` 402, `feed_unavailable` 503, `feed_unlicensed` 503, `rate_limited` 429.

**Apps**
* A Radar screen opens from the airport card ("Ground radar") and from Settings / the flight list ("Planes near me").
* It uses the existing offline map engine (Android MapLibre bundled land; iOS MapKit) and shows rotated plane icons with callsign labels. It refreshes every 10 s while the screen is visible.
* Location: coarse location only, asked for on tap (Android `ACCESS_COARSE_LOCATION`; iOS when-in-use reduced accuracy).
* When the feed is unavailable or unlicensed, the screen shows a friendly "Radar is coming soon" state, never an error.

## 2. TripIt sync (Pro)

**Secrets:** `TRIPIT_CONSUMER_KEY`, `TRIPIT_CONSUMER_SECRET`. TripIt API v1 uses OAuth 1.0a (HMAC-SHA1).

**Table `tripit_connections`** (migration 32, service-role only)
* Columns: `user_id` (pk), `request_token`, `request_secret`, `access_token`, `access_secret`, `connected_at`, `last_sync_at`, `last_error`.
* Tokens are stored encrypted with pgsodium / Vault if available; otherwise in plain text in a service-role-only table.
* RPC `tripit_status() returns jsonb`: `{connected, last_sync_at}` for the caller.

**Edge Functions**
* `tripit-connect` (JWT): gets a request token and returns `{ "authorize_url": "https://www.tripit.com/oauth/authorize?oauth_token=...&oauth_callback=<tripit-callback url>" }`.
* `tripit-callback` (public GET): exchanges for an access token, then redirects to `heygate://tripit/connected` (or shows a plain "Connected, you can go back to the app" page).
* `tripit-sync` (JWT for manual sync; `x-cron-secret` for the hourly cron, migration 32):
  1. `GET /v1/list/trip/traveler/true/past/false/include_objects/true/format/json`.
  2. For each `AirObject.Segment`, it reads carrier + flight number + local start date + start airport code.
  3. It calls `searchFlight`, then tracks the flight with `source = 'tripit'` (added to the `user_flights.source` check).
  4. Segments already tracked are skipped, so the sync is idempotent.
* `tripit-disconnect` (JWT): deletes the row.
* Non-Pro users get `pro_required`.

**Apps:** Settings → "Connect TripIt" opens `authorize_url` in a browser or Custom Tab. The deep link `heygate://tripit/connected` refreshes the status. The screen has "Sync now" and "Disconnect".

## 3. Gift Pro

**Products**
* `heygate_gift_year`: consumable, ₹4,999, gives 365 days.
* `heygate_gift_trip`: consumable, ₹99, gives 7 days.
* Neither is attached to the `pro` entitlement.

**Table `gift_codes`** (migration 33)
* Columns: `code` (pk, 12 chars `XXXX-XXXX-XXXX` from the invite alphabet), `purchaser_id`, `product_id`, `days`, `store_transaction_id` (unique), `created_at`, `redeemed_by`, `redeemed_at`, `voided_at`.

**Webhook**
* `revenuecat-webhook`, on `NON_RENEWING_PURCHASE` of a gift product, creates **one** code per transaction (idempotent on `store_transaction_id`). It does **not** give the purchaser Pro.
* On `CANCELLATION` (refund) of a gift transaction, it sets `voided_at` when the code has not been redeemed. If it has been redeemed, nothing changes, and this is logged.

**RPCs**
* `my_gifts() returns jsonb`: the purchaser's codes and their status (`unused` | `redeemed` | `voided`).
* `redeem_gift(p_code text) returns jsonb`: `{ pro_until }`.
  * It sets `pro_until = greatest(now(), coalesce(pro_until, now())) + days` and `plan = 'pro'`.
  * Errors: `invalid_code` (P0002), `already_redeemed` (23505), `voided` (22023), `own_gift` (22023, you can't redeem your own gift).
  * Failed attempts are rate-limited the same way as P3 friend invites: an attempts table, max 10 failures per hour per user. The function **returns** `{error}` instead of raising, so the attempt is recorded.

**Apps**
* The paywall gets a "Gift Pro" tab: buy a gift product, then show the code with Share / WhatsApp ("I've gifted you HeyGate Pro! Code: …").
* Settings has "Redeem a gift code" and "My gifts".
* Gift text is in en/hi; Hinglish is used in the share text.

## 4. Terminal maps (Pro)

**Data:** OpenStreetMap through Overpass. The build-time script `scripts/build-terminal-maps.mjs` covers DEL BOM BLR HYD MAA CCU COK AMD.
* Query features within each airport's `aeroway=aerodrome` area:
  * `aeroway=terminal|gate|apron|parking_position|taxiway|runway|jet_bridge`
  * `amenity=*` and `shop=*` inside terminal polygons (toilets, food, ATM, lounge, prayer room, pharmacy, charging)
  * `building=*` with `aeroway=terminal`
* The output is one simplified GeoJSON per airport, under ~400 KB, with properties `{kind, ref, name, level?}`.
* It is written to `data/terminal-maps/{IATA}.geojson`, copied into `android/app/src/main/assets/terminal-maps/` and `ios/FlyTrack/Resources/TerminalMaps/`.
* Every map shows the attribution **"© OpenStreetMap contributors (ODbL)"**. A `data/terminal-maps/LICENSE.md` states ODbL and that the data is a Produced Work.
* If Overpass is unreachable when the script runs, it exits clearly, and the apps show "Terminal map not available yet" for airports with no file.

**Apps: a Terminal map screen**
* Opened from the departure and arrival airport cards.
* Shows terminal outlines, gates with their numbers, aprons and taxiways.
* Highlights **the user's gate** (from `dep_gate`) and the arrival belt area when it is known.
* Has a POI list (food, toilets, lounges, ATM, prayer room) with a search filter.
* It works offline from the bundled files.

## 5. Migration numbering

| File | Contents |
|---|---|
| `20261127000031_radar.sql` | `radar_quota` |
| `20261127000032_tripit.sql` | `tripit_connections`, `tripit_status`, `tripit` source, hourly cron |
| `20261127000033_gifts.sql` | `gift_codes`, `gift_attempts`, `my_gifts`, `redeem_gift` |
| `20261127000034_terminal_maps.sql` | nothing server-side unless needed; reserved |

## 6. Ownership

| Area | Owns |
|---|---|
| backend-p4 | migrations 31–33; functions `radar`, `tripit-*`; gift handling inside `_shared/billing.ts` + `revenuecat-webhook`; `_shared/positions.ts`, `_shared/oauth1.ts`; tests |
| terminal-maps-data | `scripts/build-terminal-maps.mjs`, `data/terminal-maps/**`, the asset copies on both platforms |
| android-p4 | `feature/radar`, `feature/tripit`, `feature/gift`, `feature/terminalmap`; minimal wiring edits |
| ios-p4 | the same four features on iOS |
| i18n | Hindi for all new strings |

## 7. Backend implementation notes (for the app areas)

These pin down details the sections above leave open. Names match migrations 31–33.

* **Radar.** `GET /functions/v1/radar?...` with the user JWT. Besides the listed errors: `unauthorized` 401, `bad_input` 400 (bad mode / coordinates / IATA), `not_found` 404 (unknown airport), `feed_error` 502 (the feed failed; show the same friendly empty state and retry on the next 10 s tick). Ground mode centres on the airport's exact coordinates (public data, not rounded); proximity rounds to 0.05° before anything else. `radius_km` is 5 (ground) or 75 (proximity). Aircraft without a callsign have `callsign: ""`; `flight` is present only when matched (format `"6E2341"`, no space). `alt_ft` is 0 on the ground; `vrate_fpm` and `reg` may be absent. A cache hit still counts against the 60 / 10 min quota.
* **TripIt.** `tripit-connect`, `tripit-sync` and `tripit-disconnect` are `POST` with the user JWT. `tripit-sync` returns `{segments, added, existing, not_found, skipped, errors}`. Errors: `pro_required` 402, `not_connected` 409, `tripit_revoked` 409 (the user revoked access in TripIt: offer "Connect again"), `tripit_error` 502, `not_configured` 503 (no consumer key yet: hide or disable the row). `tripit-disconnect` works without Pro. `tripit-callback` redirects to `heygate://tripit/connected`, or `heygate://tripit/failed?error=denied|expired|tripit_error|not_configured|internal`. `tripit_status()` also returns `last_error` (`tripit_revoked` | `tripit_error` | null). A first sync starts in the background right after connecting; flights a user archived are never brought back by a sync.
* **Gifts.** `redeem_gift` returns `{pro_until, days}` on success, otherwise `{error}` with one of `invalid_code`, `already_redeemed`, `voided`, `own_gift`, `rate_limited` (10 failures in the last hour), `lifetime_pro` (the caller already has Pro without an end; the code is not used up). Input is normalised (case, spaces, dashes). `my_gifts()` items: `{code, product_id, days, status, created_at, redeemed_at}`, newest first. After a purchase the code appears in `my_gifts()` once RevenueCat's webhook has arrived (usually seconds): poll `my_gifts()` briefly after the purchase completes. Redeemed gift time is kept by billing when a subscription later expires or is refunded; after a refund (or a TRANSFER away) the gift's days run from the refund (or from redemption), so a cheap gift never preserves refunded subscription time.

## 8. Status (27 Nov 2026)

**Built**
* **Backend:** radar (`positions.ts`, `radar`, `radar_quota`), TripIt (`oauth1.ts`, which reproduces the RFC 5849 vectors; `tripit-*` functions; hourly cron), Gift Pro (one code per transaction, refunds void unused codes, gift time can't survive a refund of the plan underneath it).
* **Apps:** Android and iOS screens for all four features.
* **Hindi:** Android app 1209/1209 strings, wear 23/23, iOS catalogs.

**Verified here**

| Check | Result |
|---|---|
| Deno tests | 292 passed |
| SQL suite | all files pass |
| `node --test scripts/build-terminal-maps.test.mjs` | 20 passed |
| Android app tests | 1059 passed |
| Android wear tests | 17 passed |
| `assembleDebug` (app + wear) | succeeds |
| iOS | **not compiled** |

**Not done here**
* **Terminal-map data is not generated.** overpass-api.de resets every connection from this sandbox. Run `node scripts/build-terminal-maps.mjs` on a machine with normal internet, then commit `data/terminal-maps/*.geojson` and the asset copies. Until then the apps show "Terminal map not available yet".
* **No live tests against the external services.** Nothing was tried against real TripIt / OpenSky / ADS-B Exchange; only fixtures were used.

**Before enabling**
* **TripIt:**
  * Set `TRIPIT_CONSUMER_KEY` / `TRIPIT_CONSUMER_SECRET`.
  * **Fix the login CSRF first.** TripIt sends no `oauth_verifier`, so a stranger's authorize link could import the victim's trips into the stranger's account. The fix is for the callback to hand back a nonce, and for the app's authenticated call to finish the exchange.
  * Add a 30–60 s cooldown on manual sync.
* **Radar:** buy a commercial feed licence, then set `RADAR_FEED=opensky|adsbx`, its credentials, and `RADAR_FEED_LICENSED=true`.
* **Gift products:** create `heygate_gift_year` (₹4,999) and `heygate_gift_trip` (₹99) as consumables, not attached to the `pro` entitlement.
