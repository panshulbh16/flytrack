# P2 contracts, wave 2 (backend ⇄ Android ⇄ iOS)

These names are fixed for wave 2. P1 and P2 wave 1 contracts still apply.

Migrations take numbers 17–20. Never edit migrations 01–16.

## 1. Friends ("Mom's flight has landed"), free

**Tables**

`friend_invites`
* Columns: `code` (pk, 8 chars from `ABCDEFGHJKMNPQRSTUVWXYZ23456789`), `user_id`, `created_at`, `expires_at` (+7 days), `used_by`, `used_at`.
* Owners can select their own rows.

`friendships`
* Columns: `user_low`, `user_high` (uuid, `user_low < user_high`, primary key on the pair), `created_at`.
* `notify_low` (bool, default true): `user_low` gets alerts for `user_high`'s flights. `notify_high` works the other way round.
* Each side can select their own rows.

**Changes to existing tables**
* `user_flights.share_with_friends` (bool, default true): when false, friends don't see that flight.
* `profiles.display_name` is used for names, falling back to "Your friend" / "Aapka dost".

**RPCs** (authenticated)

| RPC | Behaviour |
|---|---|
| `create_friend_invite() returns text` | Reuses an unexpired, unused code |
| `accept_friend_invite(p_code text) returns jsonb` | Returns `{friend_id, display_name}`. Errors: `invalid_code` (P0002), `own_code` (22023), `already_friends` (23505), `friend_limit` (P0001, max 50) |
| `remove_friend(p_friend uuid)` | |
| `set_friend_alerts(p_friend uuid, p_enabled bool)` | Turns on or off the alerts I get for that friend |
| `my_friends() returns jsonb` | `[{friend_id, display_name, alerts_enabled, since}]` |
| `friends_flights() returns jsonb` | Friends' flights that are upcoming or departed within the last 24 h, with `share_with_friends = true`: `[{friend_id, display_name, flight: <same public fields as shared-flight JSON>}]` |

**`can_read_flight`** also returns true for flights tracked by an accepted friend, when that flight has `share_with_friends = true`.

**Friend alerts**
* Event types: DEPARTED, LANDED, CANCELLED, DIVERTED, DIVERTED_DEST, and DELAY only when `delayMin ≥ 30`.
* Sent to friends with alerts enabled who don't track the flight themselves.
* Recipient role is `friend`, and the text names the friend:
  * en: "Asha's flight 6E 2341 has landed in Mumbai"
  * hi: "Asha की फ़्लाइट 6E 2341 मुंबई पहुँच गई"
  * hinglish: "Asha ki flight 6E 2341 Mumbai land ho gayi"
* FCM data adds `role="friend"` and `friend_name`.
* `get_flight_recipients` returns a `role` column (`tracker` | `friend`) and `friend_name`.
* Pro-only events (INBOUND_LATE) never go to friends.

## 2. Email forwarding import (Pro)

**Forwarding address**
* `profiles.inbox_token` (10 chars, unique, created on first request).
* RPC `my_forwarding_address() returns text` returns `trips+{token}@{INBOUND_EMAIL_DOMAIN}`, with the domain from table `app_config(key, value)` key `inbound_email_domain`, default `in.heygate.app`.

**Edge Function `inbound-email`**
* POST, `verify_jwt = false`, header `x-inbound-secret` must equal the secret `INBOUND_EMAIL_SECRET`.
* Body (provider-neutral; a small adapter maps Postmark / SendGrid / Cloudflare Email Workers to it):
  `{ "to": "...", "from": "...", "subject": "...", "text": "...", "html": "...", "attachments": [{ "filename", "content_type", "content_base64" }] }`
* Processing:
  1. Find the user by token.
  2. Reject (200, ignored) if the user is not Pro (`is_pro`) or the email is over 5 MB.
  3. Strip HTML to text.
  4. Parse with the existing `parse-ticket` logic (`_shared/parse-ticket.ts`). PDF attachments only go through if text can be extracted; otherwise skip.
  5. For each leg found, run `searchFlight`, then track it with `source = 'email'` (add `email` to the `user_flights.source` check).
  6. Push `kind="import"`: "Added 2 flights from your email".
* Every attempt is stored in `email_imports(id, user_id, received_at, from_domain, subject_hash, status ('added'|'no_flights'|'not_pro'|'error'), flights_added int, error)`.
* The email body itself is **not** stored, for DPDP data minimisation.

## 3. Calendar (free)

**Feed**
* `profiles.calendar_token` (32 chars).
* RPCs: `my_calendar_url() returns text` and `rotate_calendar_token() returns text`.
* URL: `{SUPABASE_URL}/functions/v1/calendar-feed?t={token}`.

**Edge Function `calendar-feed`**
* GET, `verify_jwt = false`, returns `text/calendar; charset=utf-8`.
* It is RFC 5545 VCALENDAR with `X-WR-CALNAME:HeyGate flights` and `REFRESH-INTERVAL;VALUE=DURATION:PT1H`.
* It contains one VEVENT per tracked flight from the last 30 days and all future ones:
  * `UID` = `{flight_id}@heygate.app`
  * `DTSTART` / `DTEND` in UTC (best known dep and arr times)
  * `SUMMARY` = "✈ 6E 2341 DEL→BOM"
  * `LOCATION` = dep airport name + terminal
  * `DESCRIPTION` = status, gate, belt, last updated
* Lines are folded and text escaped per the RFC.

**Device calendar (apps)**
* "Add to calendar" on the detail screen writes one event, through Android `CalendarContract` insert intent / iOS EventKit.
* Settings → "Subscribe in Google/Apple Calendar" copies or opens the feed URL (`webcal://` for iOS).

## 4. Arrival forecast + on-time history (Pro)

**RPC `flight_forecast(p_flight_id uuid) returns jsonb`**
* Allowed when the caller can read the flight. The detail is null when they are not Pro for that flight.

```json
{ "history": { "samples": 23, "window_days": 60, "on_time_pct": 61, "avg_dep_delay_min": 14,
                "median_arr_delay_min": 6, "cancelled": 1 },
  "forecast": { "arr_p50": "...", "arr_p80": "...", "method": "history|schedule", "confidence": "low|medium|high" },
  "detail_locked": false }
```

**Source data**
* History comes from `flight_leg_history` for the same carrier + number in the last 60 days.
* When there are fewer than 5 samples, it uses the same carrier + route, and then just the route.
* Confidence: high at 20 or more samples, medium at 8 or more, otherwise low.

**Forecast**
* While not departed: `arr = current est/sched dep + sampled block time quantiles`.
* Once departed: `act_dep + block time`, capped against the provider's `est_arr`.
* If there is no history: `method = "schedule"`.

## 5. When to leave (Pro; computed on the device)

**`leaveBy(depTime, international, travelMinutes, fogAtDep, peak)`**

`leaveBy = depTime − buffer − travelMinutes`, where:

* the base buffer is 120 min for domestic and 180 min for international
* add 30 min when Airport Health for the departure airport shows `fog` or a level of `disrupted` or worse
* add 20 min in peak hours (05:00–09:00 and 17:00–21:00, departure airport local time)

**Travel time**
* The user enters it once per airport and the apps remember it (default 60 min).
* A "Get directions" button opens Google Maps / Apple Maps to the airport.

**Display**
* A card shows "Leave by 05:40".
* A local notification fires at `leaveBy − 15 min`. It is scheduled on the device, so no server is involved.

**Tests** use the same fixtures on Android and iOS.

## 6. Migration numbering

| File | Contents |
|---|---|
| `20261126000017_friends.sql` | friends |
| `20261126000018_email_import.sql` | email import, `app_config`, `email` source |
| `20261126000019_calendar.sql` | calendar tokens |
| `20261126000020_forecast.sql` | `flight_forecast` |

## 7. Ownership

| Area | Owns |
|---|---|
| backend-friends | migration 17; friend fan-out in `engine.ts` / `notify.ts` (friend templates in 3 languages); `get_flight_recipients` update; `can_read_flight` update; tests |
| backend-import-calendar-forecast | migrations 18–20; functions `inbound-email`, `calendar-feed`; `_shared/ics.ts`, `_shared/forecast.ts`, `_shared/email.ts`; tests |
| android-wave2 | `feature/friends/**`, `feature/forecast/**`, `feature/leave/**`, calendar actions, forwarding-address screen; minimal edits to detail, settings and nav |
| ios-wave2 | everything iOS for sections 1–5 |
| i18n | after the builders: Android `values-hi/strings*.xml` (every string file), iOS `hi` localisation |
