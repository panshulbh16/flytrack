# P1 contracts (backend ⇄ Android ⇄ iOS)

These names are fixed for P1. Backend, Android and iOS code must match them exactly. If something has to change, change this file first.

Prices (founder decision, 26 Nov 2026): **₹4,999/yr · ₹499/month · ₹99 trip pass**. Prices live in the Play / App Store consoles, never in code.
The free tracking limit stays **10 active flights** (Pro: 50).

## 1. Billing (RevenueCat)

**Store and RevenueCat setup**

| Item | Value |
|---|---|
| Entitlement id | `pro` |
| Offering id | `default` |
| Package: annual | `$rc_annual` → product `heygate_pro_yearly` (auto-renewing, ₹4,999) |
| Package: monthly | `$rc_monthly` → product `heygate_pro_monthly` (auto-renewing, ₹499) |
| Package: trip pass | custom package id `trip_pass` → product `heygate_trip_pass` (**consumable**, ₹99, can be bought again; grants Pro for **7 days** from purchase). **Do not attach it to the `pro` entitlement** in RevenueCat, because RevenueCat would make it lifetime. The backend computes the 7 days from `subscriber.non_subscriptions.heygate_trip_pass[].purchase_date` and the apps from CustomerInfo's `nonSubscriptionTransactions` |

**RevenueCat app user id**
* It is the **Supabase auth user id** (uuid).
* The apps call `logIn(userId)` after sign-in. That includes anonymous users, and the id stays the same when Google is linked.

**Public SDK keys**
* Android: `REVENUECAT_ANDROID_KEY`, from `local.properties` or the environment, exposed as a BuildConfig field.
* iOS: `REVENUECAT_IOS_KEY`, from `Config/*.xcconfig` into Info.plist.
* If a key is blank, billing is disabled: the paywall shows "Pro coming soon" and nothing crashes.

**Backend source of truth:** `profiles.plan` / `profiles.pro_until`. The apps may show Pro immediately from RevenueCat CustomerInfo (optimistic), but every server feature uses the profile.

**Edge Function `revenuecat-webhook`**
* POST, `verify_jwt = false`.
* Header `Authorization` must equal the secret `REVENUECAT_WEBHOOK_AUTH`, compared in constant time.
* It is idempotent by `event.id` (table `billing_events`).
* On any event for `app_user_id` (and `transferred_from` / `transferred_to` for TRANSFER):
  * If `REVENUECAT_SECRET_API_KEY` is set, it re-fetches `GET https://api.revenuecat.com/v1/subscribers/{id}` and applies the **current** `pro` entitlement (`expires_date`, where null means lifetime), plus the latest trip-pass purchase + 7 days, taking the later of the two.
  * Otherwise it derives state from the event:
    * `INITIAL_PURCHASE`, `RENEWAL`, `UNCANCELLATION`, `PRODUCT_CHANGE`, `SUBSCRIPTION_EXTENDED` → `pro_until = expiration_at_ms`
    * `NON_RENEWING_PURCHASE` with `heygate_trip_pass` → `pro_until = greatest(pro_until, purchased_at + 7 days)`
    * `CANCELLATION` → no change (still Pro until expiry)
    * `EXPIRATION` → `plan = 'free'`, `pro_until = null` unless another active grant exists
    * `BILLING_ISSUE` → no change (grace period)
    * `TEST` → 200, no-op
* It sets `plan = 'pro'` when `pro_until > now()`.
* Unknown user ids are logged and get 200, so RevenueCat doesn't retry forever.

**Edge Function `sync-entitlement`**
* POST, user JWT.
* It re-fetches RevenueCat for the caller and updates their profile.
* The apps call it after a purchase or restore.
* Response: `{ "plan": "pro"|"free", "pro_until": iso|null, "trial_flight_id": uuid|null }`.

**Free Pro on the first flight (trial)**
* Column `profiles.trial_flight_id uuid null`, plus `profiles.trial_used boolean not null default false`.
* When a **non-Pro** user with `trial_used = false` inserts their first `user_flights` row, a trigger sets `trial_flight_id` to that flight and `trial_used = true`.
* `public.is_pro_for_flight(p_user uuid, p_flight uuid)` = `is_pro(p_user) or trial_flight_id = p_flight`.
* It is used in `refresh_flight_trackers` (pro count), `get_flight_recipients` (`is_pro` per flight) and the Pro masking in `flight_view`.

**Apps**
* They read `profiles.plan`, `pro_until` and `trial_flight_id`.
* On the trial flight, show the badge "Pro free for this flight" / "Is flight pe Pro free".

## 2. Share a flight (WhatsApp first)

**Table `flight_shares`**
* Columns: `id`, `token` (unique, at least 22 chars URL-safe random), `user_id`, `flight_id`, `created_at`, `revoked_at`, `expires_at` (default: the flight's arrival + 2 days).
* RLS: owners can select their own rows.

**RPCs**
* `create_flight_share(p_flight_id uuid) returns text`
  * Auth required, and the caller must track that flight.
  * Returns the caller's existing active token, or creates one.
* `revoke_flight_share(p_token text) returns void`: owner only.

**Edge Function `shared-flight`**
* `GET ?t=<token>`, `verify_jwt = false`, CORS `*`.
* Returns 404 for an unknown, revoked or expired token.
* Returns **public fields only**: no PNR, seat, user or device data.

```json
{ "flight": { "carrier": "6E", "number": "2341", "date": "2026-11-25", "airline": "IndiGo",
  "status": "en_route", "sched_dep": "...", "est_dep": null, "act_dep": "...",
  "sched_arr": "...", "est_arr": "...", "act_arr": null,
  "dep": { "iata": "DEL", "name": "...", "city": "...", "lat": 0, "lon": 0, "tz": "Asia/Kolkata", "terminal": "1", "gate": "42B" },
  "arr": { "iata": "BOM", "name": "...", "city": "...", "lat": 0, "lon": 0, "tz": "Asia/Kolkata", "terminal": "2", "gate": null, "belt": "5" },
  "aircraft": { "model": "A320neo" },
  "live": { "lat": 0, "lon": 0, "alt_ft": 0, "speed_kts": 0, "at": "..." } | null,
  "updated_at": "..." } }
```

**Public web page**
* Lives in `web/share/`: static files, no build step, deployable to GitHub Pages / Cloudflare Pages.
* It reuses HeyTurbulence's globe and great-circle drawing and its palette.
* URL form: **`{SHARE_BASE_URL}#<token>`**, with `SHARE_BASE_URL` defaulting to `https://heygate.app/f/`.
* The page reads the token from `location.hash` and fetches `{SUPABASE_URL}/functions/v1/shared-flight?t=<token>`.

**Apps**
* "Share flight" on the detail screen calls `create_flight_share`, then shares this text:
  `"Track my flight {carrier} {number} {DEP}→{ARR} live: {url}"`
  `"Meri flight live track karo: …"`
* Offer WhatsApp directly when it is installed (Android package `com.whatsapp` / `com.whatsapp.w4b`; iOS `whatsapp://send?text=`), otherwise the system share sheet.

## 3. Check-in reminders (free)

**Table `checkin_rules`**

| Column | Notes |
|---|---|
| `carrier_iata` | primary key |
| `opens_hours_before` | int |
| `closes_minutes_before` | int, domestic |
| `closes_minutes_before_intl` | int |
| `checkin_url` | |
| `verified` | bool |
| `notes` | |
| `updated_at` | |

It is readable by authenticated users.

Seed:

| Carrier | Opens | Closes (domestic / intl) | URL | Verified |
|---|---|---|---|---|
| 6E (IndiGo) | 48h | 60 / 75 min | `https://www.goindigo.in/web-check-in.html` | yes |
| AI (Air India) | 48h | 60 / 75 min | `https://www.airindia.com/in/en/manage/web-checkin.html` | no |
| IX (Air India Express) | 48h | 60 / 75 min | `https://www.airindiaexpress.com/checkin-home` | no |
| QP (Akasa) | 48h | 60 / 75 min | `https://www.akasaair.com/check-in` | no |
| SG (SpiceJet) | 48h | 60 / 75 min | `https://www.spicejet.com/check-in` | no |

**Columns on `flights`**
* `checkin_opens_at`, `checkin_closes_at` (timestamptz): maintained by a trigger from the rules and `sched_dep` / `est_dep`. An international flight is one where the dep and arr airport countries differ.
* `checkin_open_notified`, `checkin_closing_notified` (bool default false).

**Events**
* `CHECKIN_OPEN`: when `now ≥ checkin_opens_at`.
* `CHECKIN_CLOSING`: when `now ≥ checkin_closes_at − 30 min` and still before the close.
* Both are severity `info` / critical respectively, `pro_only = false`.
* The payload includes `checkin_url` and `closes_at`.
* Sent by the regular poll cycle, which runs a DB-only due-check every tick and makes no provider calls. `invoke_poll_flights()`'s pre-check must also fire when a reminder is due.

**FCM data**
* `kind="alert"`, `event_type="CHECKIN_OPEN"|"CHECKIN_CLOSING"`, `checkin_url=<url>`.
* Tapping it opens the airline URL in the browser.
* Users can mute it with the `notify_prefs` keys `CHECKIN_OPEN` / `CHECKIN_CLOSING`.

**Apps:** the detail screen shows "Web check-in opens in 6h" or "Check in now →" from `checkin_rules` plus the flight times.

## 4. History and coverage

**Table `flight_leg_history`**
* One row per finished leg, upserted when polling stops (`next_poll_at` becomes null).
* Columns: `flight_id` (pk), carrier, number, date, dep, arr, `sched_dep`, `act_dep`, `sched_arr`, `act_arr`, `dep_delay_min`, `arr_delay_min`, `dep_terminal`, `dep_gate`, `arr_terminal`, `baggage_belt`, `aircraft_reg`, `aircraft_model`, `final_status`, `had_gate`, `had_belt`, `had_tail`, `had_position`, `provider`, `finalized_at`.

**Flag `flights.live_seen`**: a boolean, set the first time a live position arrives.

**View `provider_coverage_daily`**
* Shows, per dep airport and day, the percentage of finished legs with terminal, gate, belt, tail and position.
* Script: `scripts/coverage-report.sql` (metros DEL, BOM, BLR, HYD, MAA, CCU).

## 5. Other fixes in P1

* `user_flights.source` adds the value **`scan`** (boarding-pass barcode).
* `flight_view` rows are limited to `can_read_flight(id)`:
  * flights the caller tracks,
  * the inbound legs of flights the caller tracks as Pro or as a trial flight.

  `search-flight` returns its own whitelisted column set through the service role, so search results don't depend on `flight_view`.
* `reserve_search_quota(p_user, p_limit)` is an atomic per-user search quota: an advisory lock plus a `search_quota_usage` table. `search-flight` uses it before calling the provider.
* `register_device` keeps at most **5 devices per user** and evicts the oldest `last_seen_at`.
* `_shared/supabase-keys.ts` provides `secretKey()` / `publishableKey()`. Each falls back through `SUPABASE_SECRET_KEYS` (JSON) / `SUPABASE_PUBLISHABLE_KEYS` (JSON), then `FLYTRACK_SECRET_KEY` / `FLYTRACK_PUBLISHABLE_KEY`, then `SUPABASE_SERVICE_ROLE_KEY` / `SUPABASE_ANON_KEY`.

## 6. Android live notification and widget

**Live notification (Pro or trial flight)**
* Channel `flight_live`: low importance, ongoing, only alerts once.
* Shown from **T-3h until landed + 45 min**, for the single most relevant flight.
* On Android 16+ it uses `Notification.ProgressStyle` with segments: check-in → boarding → taxi/airborne → landed → belt, and asks to be promoted (Live Update). Older versions fall back to `NotificationCompat` with progress.
* Updated from FCM `sync` messages, a WorkManager tick (about 15 min) and an offline estimate (interpolate between dep and arr times, labelled "Estimated").

**Glance widget (free)**
* Shows the next or current flight: route, flight number, status word, departure time (airport-local), gate and belt, and a countdown.
* Tapping it opens the flight.

## 7. Migration numbering (avoid clashes)

| File | Contents |
|---|---|
| `20261126000008_billing.sql` | billing, trial, `is_pro_for_flight` |
| `20261126000009_security.sql` | `can_read_flight`, search quota, device cap |
| `20261126000010_share_checkin.sql` | shares, check-in rules and columns, `scan` source |
| `20261126000011_history.sql` | leg history, `live_seen`, coverage view |
