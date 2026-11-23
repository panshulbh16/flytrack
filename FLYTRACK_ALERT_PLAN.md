# FlyTrack: Alert Plan ("alert before airline, and never wrong")

Owner: Panshul Bharadwaj · Written 23 Nov 2026 · Companion to `FLYTRACK_RULES.md`
Scope: fixing the #1 complaint from the research (delay / cancel / gate alerts arriving late or never)
without creating the Bengaluru problem (a false 1-hour Air India delay pushed by Flighty, AwardWallet and Google).

> Convention: anything that **exists in the repo today** is written as a real path or name
> (e.g. `supabase/functions/_shared/diff.ts`, `flight_events.dedupe_key`). Anything **proposed** is marked **NEW**.
> Prices are estimates from public pages/brief, **re-verify before buying**.

---

## 0. Where we stand today (verified from the repo)

| Area | What exists | File / object |
|---|---|---|
| Scheduler | pg_cron every 30 s → `invoke_poll_flights()` → pg_net → Edge Function `poll-flights` (skips the call if nothing is due) | `supabase/migrations/20261122000002_cron.sql` |
| Lease / dedupe of polling | `claim_due_flights()` with `FOR UPDATE SKIP LOCKED`; one `flights` row per unique leg shared by all users | `20261122000001_init.sql` |
| Adaptive cadence | `CADENCE` + `nextPollAt()`: T-72h first poll, 6h / 90m / 20m / 10m(free) 5m(Pro) / gate window 3m(free) 2m(Pro), airborne 15m, approach 3-4m, belt window 2m, burst 2m after any change, webhook = 2x slower | `_shared/schedule.ts` |
| Single ingest path | `ingest()` for poll / webhook / search / inbound; `mergeSticky()` (gate/belt never go back to null); `isStaleSnapshot()` via `providerUpdatedAt` | `_shared/engine.ts` |
| Alert decisions | `evaluateAlerts()` against **what users were last told** (`flights.alert_state`), 15-min delay threshold, `SCHEDULE_CHANGE`, `REINSTATED` only on a status that proves operation, gate flap ignored, generation counter `alertSeq` in dedupe keys | `_shared/diff.ts`, `types.ts` |
| Exactly-once | `flight_events.dedupe_key unique`; events inserted before `alert_state` moves; `sweepUnpushedEvents()` re-sends 2-60 min old unpushed events | `engine.ts`, `init.sql` |
| Inbound plane (Pro) | `maybeLinkInbound()`, `evaluateInbound()` (inbound ETA + 35 min turn), `INBOUND_LATE` in 15-min buckets, `flights.prediction`, `inbound_watch_until` | `diff.ts`, `engine.ts` |
| Providers | `FlightDataProvider` interface; `MockProvider`; `AeroDataBoxProvider` (estDep = airline-revised only, `CanceledUncertain` → `unknown`, webhooks behind `ADB_WEBHOOKS`) | `_shared/providers/*` |
| Webhook | `provider-webhook` (`?token=` secret) → `handleWebhook()`; polling stays on as safety net | `functions/provider-webhook/index.ts` |
| Push | FCM HTTP v1 (`FcmPusher`, 3 retries, token cache, `UnconfiguredPusher` fails loudly); **notification** message + data, `priority: HIGH`, per-type TTL, tag `<flightId>:<group>`; data-only `sync` with `collapse_key`; APNs `time-sensitive` for critical | `_shared/fcm.ts`, `_shared/notify.ts` |
| Observability | `push_log` (sent / failed / invalid_token / dry_run), `provider_calls` (units, ms), `flight_status_history` | `init.sql` |
| Android | Channels `flight_critical` (HIGH) / `flight_updates` (DEFAULT); `FlyTrackMessagingService` (alert → `SystemFlightNotifier`, sync → `FlightSyncWorker` expedited → `applyServerSync`); `onNewToken` → `register_device()`; OEM battery deep links (`OemBattery.kt`), `NotificationHealth.kt`, `NotificationPermission.kt`; `FOREGROUND_SERVICE_SHORT_SERVICE` declared | `android/app/src/main/kotlin/app/flytrack/push/*`, `core/push/PushContract.kt` |
| Not yet | Nothing ran on a real Supabase / Firebase / device; widget, live notification, offline estimate, map, payments; open review items in `docs/HANDOFF.md` §4 (CAS on flight row, atomic `apply_flight_update()` RPC, etc.) | `docs/HANDOFF.md` |

**Already good for accuracy:** we only use airline-revised times for `estDep` (not AeroDataBox's ML `predictedTime`), we never alert on `CanceledUncertain`, and we compare against "last told" state.
**Gaps this plan closes:** one data source = single point of truth *and* failure; no confidence/verification stage; no delivery receipts; no lead-time measurement vs the airline; no live notification / offline estimate.

---

## 1. Faster and more accurate data

### 1.1 Sources

| Source | Role | Speed / quality (India) | Cost (approx, verify) | Legal / ToS |
|---|---|---|---|---|
| **AeroDataBox** (API.Market / RapidAPI) | **Primary** now. Status, revised times, gate/terminal/belt (when airport publishes), tail reg, position, webhooks ("Flight Alert") | Good schedules; **gate/belt at DEL/BOM/BLR/HYD/MAA/CCU unverified**; `quality[]` field per movement tells if data is Live / Basic / Approximate | ~**$99/mo Growth** tier incl. webhooks (per brief); units per call unverified | Commercial use allowed per plan terms |
| **FlightAware AeroAPI** | **Second source** (Phase 2) and future primary at scale; Alerts (push) + Firehose (stream) | Strong ADS-B + airline/ATC feeds, good delay data; India gate/belt also patchy | Usage-based, ~**$400+/mo** realistic at scale; **Firehose by quote** | Commercial OK under contract |
| **Cirium / OAG** | Enterprise-grade schedules + status, best cancel/retime data | Highest accuracy, airline-direct feeds | **By quote** (typically 4-5 figure USD/yr) | Contract |
| **Flightradar24 API** | — | Good map | — | **Do not use**: terms forbid competing flight-tracking products |
| **OpenSky / ADS-B Exchange** | Research / dev only | Raw ADS-B | Free | **Non-commercial** licences; commercial needs separate deal |
| **Airline sites / PNR status pages** (IndiGo, AI, QP, SG, IX) | Cross-check signal only (cancel / retime) | Airline's own truth, sometimes ahead of feeds | Free | **ToS often bans scraping. Do not ship scraping without legal review.** Prefer: user-forwarded airline SMS/email (user's own data) |
| **Airport FIDS pages** (DEL, BOM, BLR) | Cross-check for gate / belt / delay | Gate & belt truth at source | Free | Same caution; check robots/ToS; low-rate, cached, never core path. Ask airports (GMR / Adani / BIAL) for a data partnership later |
| **User signal** (NEW, Phase 2) | "Airline SMS aaya?" one-tap report, forwarded SMS/email parse | Real airline notice time | Free | User consent (DPDP) |

**ADS-B for inbound aircraft:** Use the provider's position for the tail (`getFlightsByRegistration()` already exists, ADB `withLocation=true`). Do not use OpenSky/ADSBx in production. With AeroAPI later, inbound position + ETA comes from the same contract.

### 1.2 Polling schedule by phase (per unique flight, all users share it)

Current `CADENCE` is already close to this; the changes are marked.

| Phase | Today (`schedule.ts`) | Proposed | Why |
|---|---|---|---|
| > T-72h | no polling, first poll at T-72h | same | Nothing changes this early except retimes |
| T-72h → T-24h | 360 min | same | Retimes / cancellations of schedules |
| T-24h → T-6h | 90 min | **60 min** | Next-day cancels (fog season Dec-Feb) |
| T-6h → T-2h | 20 min (T-6h→T-3h), 10/5 (T-3h→T-90m) | **15 min until T-3h, then 5 min (all users)** | Delays start being announced |
| T-2h → off-block (gate window) | 3 min free / 2 min Pro (from T-90m) | **2 min for all** from T-2h; webhook active → poll stays at 3 min (not 2x slower) | Core promise. A 1 min difference matters for gate changes |
| Airborne | 15 min, approach 3-4 min | same; position from provider (map), not for alerts | Few alertable events in cruise |
| Landed → belt known → +45 min | 2 min, then 5 | same | Belt alerts |
| Cancelled | recheck 30 min, stop at dep+2h | **recheck at 10 / 30 / 60 min** | Reinstatement + verification (see 1.5) |
| Pending verification (NEW) | — | **burst 1-2 min** until confirmed or expired | Speed of confirming a candidate event |

### 1.3 Webhook vs polling

| | Webhook (ADB Flight Alert, AeroAPI Alerts) | Polling (`poll-flights`) |
|---|---|---|
| Latency | Seconds after provider sees change | Up to the cadence (2 min in gate window) + 30 s cron tick |
| Reliability | Best-effort; can be late, duplicated, out of order | Deterministic, we control it |
| Cost | Credit per subscription / delivery | Per call |
| Our rule | **Webhook = fast path, polling = safety net. Never remove polling.** | |

Before enabling `ADB_WEBHOOKS=true`: fix `docs/HANDOFF.md` §4 item 1 (compare-and-swap on the flight row, NEW column `flights.alert_version`) and item 2 (atomic NEW RPC `apply_flight_update()`), because the webhook path takes no lease.
NEW: when a webhook delivers a change, immediately set `next_poll_at = now()` for a **verification poll** (cheap, confirms the webhook payload).

### 1.4 Cost math per active flight

HANDOFF simulator: **≈107 calls/flight (free only)**, **≈175-200 (any Pro tracker, inbound polling)**. Cost is per *unique* flight, shared by all its trackers.

```
cost_per_flight = calls × units_per_call × price_per_unit
example (ASSUMED $0.002 per call, 1 unit/call; verify on API.Market free tier):
  free flight ≈ 107 × 0.002 ≈ $0.21 (≈ ₹18)
  Pro flight  ≈ 200 × 0.002 ≈ $0.40 (≈ ₹34)
  proposed denser schedule above ≈ +25 calls → ≈ $0.26 / $0.45
  second source (Phase 2) only in T-6h → landing, only for verification → ≈ +20-40 calls
```

Popular routes (DEL-BOM, BLR-DEL) share one row across many users, so real cost per user is lower.
Track true cost in `provider_calls` (already logged with `units`) and NEW view `v_cost_per_flight` (sum units by `flight_id`, by day).

### 1.5 Cross-verification and confidence (the Bengaluru fix)

NEW module `supabase/functions/_shared/verify.ts`, called inside `ingest()` between `evaluateAlerts()` and `insertEventIfNew()`.
`evaluateAlerts()` keeps producing *candidate* events; `verify.ts` decides **fire now**, **hold**, or **drop**.

**Signals and weights (initial, tune with beta data):**

| Signal | Score |
|---|---|
| Primary provider, airline-revised time (`revisedTime`) or explicit status | +2 |
| Same value seen in **2 consecutive polls ≥ 3 min apart** (stability window) | +1 |
| ADB movement `quality` includes `Live` (NEW: map it in `mapAdbFlight()`) | +1 |
| Second provider agrees (Phase 2, AeroAPI) | +2 |
| Airport FIDS / user-forwarded airline SMS agrees (Phase 2-3, legal OK only) | +2 |
| Inbound aircraft data supports it (e.g. inbound late explains delay) | +1 |
| Provider `predictedTime` / ML-only estimate | 0 (never alerts as "confirmed") |
| Second provider **disagrees** | -3 → hold |
| Value flapped in last 15 min | -2 |

**Fire rules:**

| Event | Fire when | Label in copy |
|---|---|---|
| `CANCELLED` | score ≥ 4 **or** primary says `Canceled` in 2 polls 2+ min apart (max hold 5 min); `CanceledUncertain` never fires | "Airline-confirmed" only if ≥ 4 with 2 sources; else "Reported cancelled — verify with airline" |
| `DELAY` (≥ 15 min) | score ≥ 3 (airline-revised + stable, or 2 sources). Max hold 6 min, then fire as "estimated" if still consistent | "Airline-confirmed" vs "Estimated" |
| `DELAY` ≥ 60 min | require score ≥ 4 (the Bengaluru case was 1 hour) or airline-revised stable for 2 polls | same |
| `GATE_CHANGE` / `TERMINAL_CHANGE` | primary + (quality Live or 1 stable re-poll ≤ 2 min). Hold max 3 min | none (gate is factual) |
| `BOARDING`, `DEPARTED`, `LANDED`, `BELT_*` | primary alone (low harm); `DEPARTED` needs `actDep` | — |
| `INBOUND_LATE` (Pro) | as today (bucketed); always labelled "Prediction" | "Prediction, airline hasn't announced" |
| `REINSTATED` | as today (status that proves operation) + 1 stable poll | "Airline-confirmed" |

Store pending candidates in `flights.alert_state.pending` (NEW key in existing jsonb): `{type, value, firstSeenAt, score, sources[]}`. Schedule `next_poll_at` at burst (1-2 min) while pending.
Persist on the event: NEW columns `flight_events.confidence smallint`, `flight_events.sources text[]`, `flight_events.label text check (label in ('confirmed','estimated','prediction'))`, `flight_events.detected_at timestamptz` (first seen as candidate).

**Retraction:** if a fired `DELAY` is contradicted within 30 min by a score ≥ 4 on-time reading, send one `DELAY_REDUCED` ("Update: on time again") with the same tag `<flightId>:delay` so it **replaces** the wrong notification. Log it as a false positive (see §5 metrics).

### 1.6 Inbound-aircraft delay prediction

Already built: `evaluateInbound()` (inbound arr + 35 min turn). Improvements (Phase 3):
- NEW per-airline/per-airport minimum turn table (IndiGo A320 ~30-35 min at BLR, wide-body longer), NEW table `turn_times(carrier, aircraft_family, airport, min_turn_min)`.
- Use inbound *departure* delay early (2-3 h before our STD) for a first, softly worded prediction.
- Never convert a prediction into a `DELAY` alert; only the airline/provider revised time does that.

---

## 2. Android instant notification architecture

### 2.1 FCM message types (keep current design)

| Message | Used for | Behaviour |
|---|---|---|
| **Notification + data**, `priority: HIGH` (`buildAlertMessage()`) | All alerts | Shown by Play services even if our process is dead or OEM blocks background starts. Correct choice for Indian OEMs. |
| **Data-only** (`buildSyncMessage()`), HIGH near flight else NORMAL, `collapse_key sync:<id>` | Room cache, widget, live notification | Needs our process. May be dropped by aggressive OEMs → app must re-sync on open. |

FCM rules to respect:
- High priority is for **user-visible** messages. If high-priority data messages don't lead to a notification, Android/FCM can **deprioritize** the app. So: sync messages are HIGH only while the live notification is on screen (current `isNearFlight()` logic) and must update it.
- Respect FCM per-device throttling (avoid bursts to one token; collapse syncs).
- TTL per type already in `TTL_SECONDS` (stale gate change worse than none). Add `CANCELLED` and `DELAY` TTL = until departure (NEW).

### 2.2 Channels and importance

| Channel id (exists) | Importance | Events |
|---|---|---|
| `flight_critical` | HIGH, sound + vibration, heads-up | GATE_CHANGE, TERMINAL_CHANGE, DELAY, SCHEDULE_CHANGE, CANCELLED, DIVERTED, REINSTATED, BOARDING, BELT_*, INBOUND_LATE |
| `flight_updates` | DEFAULT | GATE_ASSIGNED, DEPARTED, LANDED, DELAY_REDUCED |
| `flight_live` (**NEW**) | LOW (silent, ongoing) | Live notification / progress |

`NotificationHealth.kt` already checks channel blocked state; add `flight_live`.
Category `CATEGORY_STATUS` for live, `CATEGORY_EVENT`/`CATEGORY_REMINDER` for alerts. Consider `setFullScreenIntent` **only** for cancellation inside T-3h (needs `USE_FULL_SCREEN_INTENT`, restricted on Android 14+ to calling/alarm apps, so likely **not** allowed; keep as idea, don't ship).

### 2.3 App states

| State | Alert (notification msg) | Sync (data msg) |
|---|---|---|
| Foreground | `onMessageReceived` → `SystemFlightNotifier.show()` (same tag) | `FlightSyncWorker` |
| Background / screen off / Doze | Shown by system immediately (HIGH bypasses Doze) | Delivered (HIGH) / batched (NORMAL) |
| Swiped away (stock Android, Samsung) | Shown | Usually delivered |
| Swiped away on Xiaomi/Vivo/Oppo/Realme without Autostart | **Shown** (Play services) | Often **dropped** |
| **Force-stopped** (Settings → Force stop, or some OEM "clean") | **Not delivered at all** until user opens app | Not delivered |

Mitigation for force-stop: detect on next open (NEW: compare `last_sync_received_at` vs server `push_log`) and show "Alerts band ho gaye the, ise force-stop mat karo" + battery guide.

### 2.4 Doze, App Standby, OEM killers

- Doze / App Standby buckets: HIGH-priority FCM is exempt; but apps in **restricted** bucket lose high priority. Keep the app in "active/working set" by being genuinely used; never spam HIGH.
- OEM guided flow (exists: `OemBattery.kt` deep links for Xiaomi, Vivo, Oppo/Realme (`com.oplus.securitypermission`), OnePlus, Samsung). Improve (NEW `BatteryGuideScreen`):
  1. Ask **only after the first tracked flight** (value moment), not at onboarding.
  2. Step cards per OEM (dontkillmyapp.com style): Xiaomi → Autostart ON + Battery saver "No restrictions"; Vivo → Background power "Allow" + Autostart; Oppo/Realme → "Allow auto launch" + "Allow background activity"; Samsung → remove from "Sleeping/Deep sleeping apps", set "Unrestricted".
  3. `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: Play policy allows only for qualifying use cases; safer to deep-link to `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (already the fallback).
  4. "Test alert bhejo" button → NEW Edge Function `send-test-push` → measure delivery and show ✅/❌.

### 2.5 Foreground service: only for active-flight live tracking

Rule 3 in `FLYTRACK_RULES.md`: no reliance on on-device polling. Recommended design:
- **Default (no FGS):** live notification = ongoing notification updated by HIGH sync pushes + on-device timer (chronometer / countdown, `setWhen` + `setUsesChronometer(true)` / `setChronometerCountDown(true)`), so it ticks without the network.
- **Android 16 Live Updates:** `Notification.ProgressStyle` with segments (check-in → boarding → departed → landed → belt) and `setRequestPromotedOngoing(true)` + permission `POST_PROMOTED_NOTIFICATIONS` (NEW). Fallback on Android ≤15: standard ongoing notification with progress bar.
- **FGS only if needed** (e.g. user opts into "Live tracking" for the active flight day, window T-3h → belt+30 min):
  - Android 14+ requires a type. `dataSync` is time-limited on Android 15 (≈6 h per 24 h) and expected to be phased out; `specialUse` needs a Play Console justification ("live flight status for a flight the user is on"). Pick **`specialUse`** with clear subtext, or skip FGS entirely if Live Updates is enough.
  - Stop on: landed + belt known, cancel, user dismisses, or 8 h max.
  - Existing `FOREGROUND_SERVICE_SHORT_SERVICE` is only for finishing the Room write (keep).
- NEW: `app.flytrack.live.LiveFlightNotifier` (builds ProgressStyle/fallback), `LiveFlightController` (start/stop rules).

### 2.6 FCM token lifecycle

Exists: `onNewToken` → `registerNewToken()` → `register_device()`; re-register on user change; unregister before sign-out; server deletes token on `UNREGISTERED` (`deleteDeviceByToken`).
Add (NEW): refresh `register_device()` on every app open if `last_seen_at` > 7 days; housekeeping deletes devices not seen in 60 days; cap 5 devices per user (HANDOFF §4 item 6).

### 2.7 Delivery receipts (ack back to Supabase)

Problem: when the system shows a notification message, our code doesn't run, so we can't ack every alert from the app.
- **Sync acks:** NEW RPC `ack_push(p_event_id bigint, p_kind text, p_received_at timestamptz, p_shown boolean)` called from `FlightSyncWorker` / `onMessageReceived`. Add **`event_id` to sync data** so a sync that follows an alert acks the alert too.
- **Tap acks:** when the user taps (`OPEN_FLIGHT`, `event_id` extra already in `PushContract`), call `ack_push(..., 'opened')`.
- **FCM delivery data:** enable Firebase **BigQuery export of message delivery data** (Android) for aggregate delivery %; join later via `fcm_message_id` (already in `push_log`).
- Schema (NEW): `push_log.delivered_at timestamptz`, `push_log.opened_at timestamptz` (or NEW table `push_receipts(push_log_id, kind, at)`).

### 2.8 Fallback channels (later, Pro)

- **SMS** (needs TRAI DLT template registration; HANDOFF already notes this for OTP) and **WhatsApp Business API** (template messages, per-conversation cost). Only for `CANCELLED`, `GATE_CHANGE` inside T-90m, `DIVERTED`, and only if FCM not delivered/acked within 3 min. NEW Edge Function `fallback-notify`, NEW table `fallback_log`.

### 2.9 iOS (brief)

Existing: FCM → APNs with `interruption-level: time-sensitive` for critical, `apns-collapse-id`, priority-5 background sync. Add: Time Sensitive entitlement in the app, Live Activities (ActivityKit push tokens, NEW `devices.live_activity_token`) for Phase 3, and Critical Alerts only if Apple ever grants it (unlikely for travel).

---

## 3. Alert logic

### 3.1 Events, thresholds, dedupe, priority

| Priority | Event (exists in `EVENT_TYPES`) | Threshold / rule | Dedupe key today (`diff.ts`) | Tag group (`notify.ts`) | Cooldown |
|---|---|---|---|---|---|
| P0 | `CANCELLED` | verified (§1.5) | `<id>:CANCELLED:<reinstateCount>` | `cancelled` | none |
| P0 | `DIVERTED` | status or dest change after departure | `<id>:DIVERTED` | `diverted` | none (NEW `DIVERTED_DEST` when airport announced) |
| P0 | `REINSTATED` | proving status | `<id>:REINSTATED:<gen>` | `reinstated` | none |
| P1 | `GATE_CHANGE` / `TERMINAL_CHANGE` | any change vs last told, before departure; null ignored | `<id>:GATE:<gen>:<old>><new>` | `gate` / `terminal` | 2 min hold collapse (NEW): if gate changes twice in 2 min, send only the final |
| P1 | `SCHEDULE_CHANGE` | sched shift ≥ 15 min | `<id>:SCHEDULE_CHANGE:<gen>:<min>` | `delay` | — |
| P1 | `DELAY` | first ≥ 15 min; again only if +15 min more | `<id>:DELAY:<gen>:<estDep>` | `delay` | NEW: min 10 min between two DELAY pushes unless +45 min jump |
| P1 | `BOARDING` | status boarding/gate_closed | `<id>:BOARDING` | `boarding` | once |
| P2 | `BELT_ASSIGNED` / `BELT_CHANGE` | any | `<id>:BELT:<gen>:...` | `belt` | — |
| P2 | `INBOUND_LATE` (Pro) | lateMin ≥ 10, 15-min buckets | `<id>:INBOUND_LATE:<bucket>` | `delay` | bucket |
| P3 | `DELAY_REDUCED` | back by ≥ 15 min | `<id>:DELAY_REDUCED:<gen>:<estDep>` | `delay` | — |
| P3 | `GATE_ASSIGNED`, `DEPARTED`, `LANDED` | first time | as today | own | once |

Ordering inside one ingest: send in P0 → P3 order (NEW sort in `fanOut()`; today only alerts-before-sync). If `CANCELLED` fires, suppress everything else in that ingest (already: cancellation returns early).

### 3.2 Quiet hours

NEW `user_flights.notify_prefs` keys (booleans only, constraint allows it) + NEW `profiles.quiet_start/quiet_end`:
- During quiet hours, P2/P3 go to `flight_updates` silently.
- **Exceptions (always ring):** P0 always; P1 if departure is within 12 h (a 5 AM IndiGo flight must wake you for a gate/delay change).

### 3.3 Copy (Hinglish, label-aware) — NEW `locale` branch in `renderEvent()` using `devices.locale`

| Event | Title | Body |
|---|---|---|
| DELAY (confirmed) | `6E 2341 late hai: 45 min` | `BLR→DEL ab 19:35 pe jayegi (pehle 18:50). Airline-confirmed.` |
| DELAY (estimated) | `6E 2341 shayad 30 min late` | `Abhi estimate hai, airline ne confirm nahi kiya. Hum nazar rakh rahe hain.` |
| GATE_CHANGE | `Gate badla: 6E 2341 → Gate 14 (T1)` | `Pehle gate 9 tha. Departure 18:50.` |
| CANCELLED (confirmed) | `6E 2341 cancel ho gayi` | `BLR→DEL cancel. Refund/rebooking ke liye airline se baat karein.` |
| CANCELLED (unconfirmed) | `6E 2341 cancel hone ki report` | `Airline se confirm karein. Hum 10 min mein dobara check karenge.` |
| INBOUND_LATE | `Aapka plane late chal raha hai` | `AI 505 (HYD se) 40 min late. 6E 2341 ~19:30 tak ja sakti hai. Airline ne abhi announce nahi kiya.` |
| DELAY_REDUCED (retraction) | `Update: 6E 2341 time pe hai` | `Pehle wala delay alert ab valid nahi. Departure 18:50.` |
| BELT | `Baggage belt 7: 6E 2341` | `T1, DEL.` |

---

## 4. Offline / airplane mode

| Piece | Exists | NEW |
|---|---|---|
| Last-known state | Room `FlightEntity` (mirrors `flight_view`), `AirportEntity` with lat/lon/tz; sync pushes write via `applyServerSync` | `FlightEntity.lastServerSyncAt` |
| Estimated progress | `FlightDerived.kt` has `bestDeparture`, `bestArrival`, `duration`, `hasDeparted` | NEW `core/estimate/OfflineEstimator.kt`: `progress = (now - actDep ?: estDep ?: schedDep) / (bestArr - bestDep)` clamped 0..1; remaining = bestArr - now |
| Position | — | Great-circle interpolation between `AirportEntity` dep/arr lat/lon at `progress` (slerp); heading from bearing; if last `live_lat/lon` known, interpolate from last fix to destination with remaining time |
| Labels | `FlightStatusColors.estimated` exists in theme | Always show **"Estimated · offline"** chip + "Last updated 14:32"; never show estimated gate/belt |
| Resync | No connectivity listener (ARCHITECTURE §20) | NEW `ConnectivityObserver` (NetworkCallback) → `refresh()` + flush queued `ack_push` calls; on airplane-mode off, fetch `flight_view` + recent `flight_events` (NEW `FlightEventRepository`) and show "Jab aap offline the: gate 14, belt 7" summary |
| Widget / live notification | not built | Both read the same estimator so they keep ticking offline |

Bundle top Indian + international airports (≈300 rows) as JSON for first-run offline (decision in ARCHITECTURE §5 says not bundled yet; reconsider for Phase 2).

---

## 5. Phased rollout

### Phase 0 — "make it real" (week 1)
- Create Supabase + Firebase projects; run `supabase db reset`; verify pg_cron / pg_net / Vault (HANDOFF §4 item 8).
- Add `google-services.json`; install on 3-4 real phones: Pixel/stock, Samsung, **Xiaomi/Redmi**, **Vivo or Realme**.
- Measure AeroDataBox India coverage (free quota): % flights with gate, belt, revised time at DEL/BOM/BLR/HYD/MAA/CCU.
- `send-test-push` (NEW) + battery guide checks.
- Exit: alert received with screen off on all 4 phones, app swiped away.

### Phase 1 — MVP that proves "alert before airline" (weeks 2-5)
- `verify.ts` (stability window + `quality` + labels), NEW event columns, retraction flow.
- Denser gate-window cadence (2 min all users), pending burst.
- Delivery receipts (`ack_push`, `push_log.delivered_at/opened_at`), FCM BigQuery export.
- **Lead-time measurement:** NEW table `airline_notices(id, user_id, flight_id, channel sms|email|app|announcement, event_type, received_at, raw_excerpt)`; in-app one-tap "Airline ka message aaya" + optional SMS/email share-to-app parsing. Lead time = `airline_notices.received_at − push_log.created_at` for the matching event.
- Internal beta: 20-50 people on real Indian flights (fog season helps: Dec-Jan delays at DEL).
- NEW SQL views + simple dashboard (Supabase SQL / Metabase): latency, lead time, false positives, delivery.
- Hinglish copy.

### Phase 2 — trust + daily use (weeks 6-10)
- Live notification (Android 16 ProgressStyle + fallback), `flight_live` channel; optional `specialUse` FGS.
- Home-screen widget (Glance) reading Room + estimator.
- Offline estimator + map (MapLibre great-circle), `ConnectivityObserver`.
- **Second data source** (AeroAPI adapter `_shared/providers/flightaware.ts` NEW) used for verification in T-6h → landing.
- CAS + `apply_flight_update()` RPC, then enable webhooks.

### Phase 3 — Pro and moat
- Inbound plane UI + improved turn times; `INBOUND_LATE` polish; Live Activities on iOS.
- Payments (Play Billing / App Store), Pro: inbound, early alerts, SMS/WhatsApp fallback.
- Airport partnerships for gate/belt data.

### Success metrics

| Metric | Phase 1 target | Phase 2 target | Source |
|---|---|---|---|
| Detection latency (provider change → event `created_at`) | p50 ≤ 2 min, p95 ≤ 4 min in gate window | p95 ≤ 2 min (webhooks) | `flight_status_history.observed_at`, `flight_events.detected_at` |
| Push latency (event → FCM accepted) | p95 ≤ 10 s | ≤ 5 s | `push_log.created_at` − `flight_events.created_at` |
| Delivery rate (sync ack within 5 min) | ≥ 95% (≥ 90% on Xiaomi/Vivo) | ≥ 97% | `push_log.delivered_at` |
| **Lead time vs airline** | ≥ 60% of alerts arrive before airline SMS; median ≥ +5 min | ≥ 75%, median ≥ +10 min | `airline_notices` |
| **False-positive rate** (alert later retracted/contradicted) | DELAY < 2%, CANCELLED < 0.5%, GATE < 1% | halve it | retractions + beta reports |
| Missed alerts (airline notified, we didn't) | < 5% | < 2% | `airline_notices` without matching event |
| Cost per tracked flight | ≤ $0.35 | ≤ $0.50 with 2nd source | `provider_calls` |

---

## 6. Supabase integration

### 6.1 Schema changes (all NEW, one migration `2026112X_alert_reliability.sql`)

```sql
alter table public.flight_events
  add column confidence smallint,
  add column sources    text[] not null default '{}',
  add column label      text check (label in ('confirmed','estimated','prediction')),
  add column detected_at timestamptz;              -- candidate first seen

alter table public.flights add column alert_version bigint not null default 0;  -- CAS (HANDOFF §4.1)

alter table public.push_log
  add column delivered_at timestamptz,
  add column opened_at    timestamptz;

create table public.provider_observations (      -- per-source raw readings for cross-check
  id bigint generated always as identity primary key,
  flight_id uuid references public.flights(id) on delete cascade,
  provider text not null, observed_at timestamptz not null default now(),
  status text, est_dep timestamptz, dep_gate text, baggage_belt text, quality text[], snapshot jsonb
);
create table public.airline_notices ( ... );      -- §5 Phase 1
-- flight_status_history.source check: add 'verify' (verification polls) and 'secondary'
-- later: turn_times, fallback_log, devices.live_activity_token
```
RLS: `airline_notices` insert/select own; `provider_observations` service-role only. `ack_push()` security definer, checks `push_log.user_id = auth.uid()`.

### 6.2 Edge functions and shared code

| Item | Status | Change |
|---|---|---|
| `poll-flights` / `runPollCycle()` | exists | no change to entry; pending candidates get burst polls |
| `_shared/engine.ts` `ingest()` | exists | call NEW `verify.ts`; write `provider_observations`; CAS on `alert_version` |
| `_shared/diff.ts` | exists | add cooldown/collapse rules, `alert_state.pending`, `DIVERTED_DEST` |
| `_shared/schedule.ts` `CADENCE` | exists | values in §1.2, `pending` burst |
| `_shared/notify.ts` | exists | Hinglish templates, label text, P0-P3 sort helper, TTL for CANCELLED/DELAY |
| `_shared/providers/aerodatabox.ts` | exists | map `quality[]`; measure units |
| `_shared/providers/flightaware.ts` | NEW | AeroAPI adapter (Phase 2) |
| `_shared/verify.ts` | NEW | scoring + fire/hold/drop |
| `send-test-push` | NEW | user-triggered test alert |
| `ack-push` (RPC `ack_push`) | NEW | receipts |
| `fallback-notify` | NEW (Phase 3) | SMS/WhatsApp |

### 6.3 Cron jobs

| Job | Exists | Notes |
|---|---|---|
| `flytrack-poll` every 30 s | yes | unchanged |
| `flytrack-housekeeping` 02:47 IST | yes | add: delete stale devices (60 d), prune `provider_observations` (30 d) |
| `flytrack-metrics` every 5 min | NEW | refresh materialized views below |
| `flytrack-alert-watchdog` every 1 min | NEW | P0/P1 events with no delivered sync ack after 3 min → log + (Pro) fallback |

### 6.4 Observability (NEW views on existing tables)

- `v_alert_latency`: per event, `detected_at`, `created_at`, first `push_log.created_at`, `delivered_at`.
- `v_delivery_by_oem`: join `devices.os_version/app_version` (NEW `devices.manufacturer` column) → delivery % per brand.
- `v_false_positives`: DELAY/CANCELLED events followed by a retraction within 30 min.
- `v_cost_per_flight`: `provider_calls` units by flight/day.
- `v_lead_time`: `airline_notices` vs events.
- Alerting to us: Slack/email when `push_log.status='failed'` > 5% in 10 min or `provider_calls` 429s spike.

### 6.5 How `FLYTRACK_RULES.md` is respected

| Rule | How this plan follows it |
|---|---|
| 1 India-first, Android + iOS | India airports/airlines first, Indian OEM handling; APNs path kept in `notify.ts` |
| 2 Instant notifications are the core | Everything above is about latency, delivery and correctness |
| 3 FCM + backend, FGS only where justified, no on-device polling | Server polling + FCM notification messages; FGS only for active-flight live tracking (optional `specialUse`); no device polling |
| 4 Supabase polling + push for delay/gate/cancel | Extends `poll-flights`, `diff.ts`, `fcm.ts`; no app → API calls |
| 5 Built so far | Reuses channels, `register_device()`, `FlightSyncWorker`, `OemBattery.kt` |
| 6 Remaining work | Widget, live notification, offline estimate, map in Phase 2; payments Phase 3; **Phase 0 = real-device test** |
| 7 Hinglish | Alert copy table §3.3 |
| Working notes | Keys stay in Supabase secrets / Vault; provider behind interface |

---

## 7. Open risks / questions

1. **AeroDataBox India gate/belt coverage unverified** (DEL/BOM/BLR/HYD/MAA/CCU). If weak, gate alerts (our headline) depend on a second source or airport partnership.
2. ADB units per call, webhook subject name and real pricing unverified (`ADB_WEBHOOKS=false` today).
3. Second-source cost (AeroAPI) may double data spend; limit to T-6h → landing and verification only.
4. Scraping airline/airport pages: ToS/legal risk; do not ship without legal review. Prefer partnerships and user-forwarded messages.
5. Verification adds delay (up to 3-6 min hold). Need beta data to tune: speed vs false positives.
6. OEM behaviour varies by ROM version; force-stopped apps get nothing. Real-device matrix needed.
7. Android 16 Live Updates eligibility rules (promoted notifications) and Play policy for `specialUse` FGS must be checked at implementation time.
8. Play policy on `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: use settings deep links, not the direct request, unless policy allows.
9. SMS fallback needs TRAI DLT registration; WhatsApp needs a Business account and template approval.
10. DPDP Act: user-forwarded airline SMS/email = personal data; store minimal excerpt, consent screen, retention limit.
11. Open HANDOFF items: CAS/atomic RPC before webhooks; `flight_view` row visibility; device fan-out cap; new Supabase API key names.
12. Nothing has run on real Supabase, Firebase or devices yet. Phase 0 is mandatory before any claim of "faster than airline".
