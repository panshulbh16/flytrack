# FlyTrack (working name), an India-first Android flight tracker

Monorepo:

| Path | What |
|---|---|
| `supabase/` | Postgres schema + Edge Functions (Deno/TypeScript): the only thing that ever calls the flight API |
| `android/` | Kotlin + Compose app (**Step 3**, not started yet) |
| `docs/design-brief.md` | UI tokens matched to HeyTurbulence (Roamly) |
| `scripts/` | airport import, SQL test runner |

## How it works (Step 2)

```
pg_cron (every 30 s) ──► invoke_poll_flights()  ──(skips if nothing due)──►  poll-flights Edge Function
                                                                              │ claim_due_flights() (SKIP LOCKED lease)
                                                                              ▼
                                          FlightDataProvider (mock | AeroDataBox | FlightAware later)
                                                                              ▼
                     ingest(): sticky-merge → diff → alerts vs "what users were last told" → history
                                                                              ▼
                     flight_events (dedupe_key UNIQUE = exactly-once) ──► FCM push immediately (same invocation)
```

* **One row per unique flight leg** (`flights`), shared by all users. 1 user or 10,000 users, the cost is the same.
* **Adaptive polling** (`_shared/schedule.ts`). The first poll happens at T-72h, then 6 h, then 90 min, then 20 min, then 5–10 min.
  * **Gate window** (T-90 min → takeoff): every 2 min for Pro, every 3 min for free.
  * In the air: every 15 min, then every 3–4 min for the last 40 min before landing.
  * **Belt window** (landing → belt known): every 2 min.
  * Polling stops 45 min after landing. Flights nobody tracks are **never** polled.
* **Instant alerts.** Gate, belt, delay and cancellation alerts are pushed in the *same* function run that detects them, with no queue.
  * They are sent as FCM **notification** messages on a high-priority channel. Google Play services shows them even when Xiaomi, Vivo, Oppo or Realme battery savers have killed the app.
  * A separate collapsible data-only **sync** message updates the widget and live notification.
* **Alert types:** delay, delay reduced, **reschedule (airline retime)**, gate assigned or changed, terminal change, boarding, departed, landed, belt assigned or changed, cancelled, cancellation withdrawn, diverted, and Pro "your plane is late".
* **Reliable delivery.** Event rows are written *before* the alert state. A sweeper re-sends anything inserted but not pushed, and FCM sends are retried 3× on network errors, 429 and 5xx.
* **No alert spam.** Alerts compare against `alert_state` (what users were last told), not the raw previous poll. A gate that flaps (gate → null → same gate) is silent. Delay alerts fire only on changes of 15 min or more.
* **Where's my plane (Pro).** Once the tail number is known (≤10 h before departure), we find the aircraft's previous leg by registration and watch it.
  * We predict `ready = inbound ETA + 35 min turnaround`.
  * If that's 10 min or more past our departure, Pro users get `INBOUND_LATE`, usually **well before** the airline announces a delay. In the simulator it came 132 min earlier.
* **Webhooks.** AeroDataBox Flight Alerts are supported but off by default (`ADB_WEBHOOKS=false`). They are credit-billed and best-effort, so polling always stays on as the safety net, just slower.

### Measured API cost (mock simulation, one unique flight, all its trackers together)

| Who tracks it | Provider calls per flight |
|---|---|
| Free users only | **≈107** |
| At least one Pro user (adds inbound-aircraft polling) | **≈175–200** |

Tune it in `CADENCE` in `_shared/schedule.ts`. The `provider_calls` table logs every real call so you can see the actual bill.

## Run & test locally

You need Deno 2 and Postgres (for the SQL tests). For the full local stack you need the Supabase CLI and Docker.

Unit and engine tests (53 tests; no network, no DB):

```bash
cd supabase/functions && deno test
```

Watch a flight's whole day with a fake clock. The last digit of the flight number picks the scenario: 5 = gate change, 7 = inbound late, 6 = cancelled, and so on (see `providers/mock.ts`).

```bash
cd supabase/functions && deno run tests/simulate.ts 2345 2347 2346
```

SQL tests (RLS, triggers, poller lease, limits) against a throwaway local Postgres:

```bash
scripts/test-sql.sh
```

Full local Supabase stack (Docker):

```bash
supabase start
supabase db reset
cp supabase/functions/.env.example supabase/functions/.env
supabase functions serve --env-file supabase/functions/.env
```

`supabase db reset` applies the migrations plus `seed.sql` (7,917 airports).

### Try the add-flight API locally

Get an anonymous user JWT. The URL and `ANON_KEY` are printed by `supabase start`.

```bash
curl -s -X POST "$SUPABASE_URL/auth/v1/signup" -H "apikey: $ANON_KEY" -H "content-type: application/json" -d '{}' | jq -r .access_token
```

Search for a flight. The mock is free; `6E 9999` returns not found.

```bash
curl -s -X POST "$SUPABASE_URL/functions/v1/search-flight" -H "authorization: Bearer $JWT" -H "content-type: application/json" -d '{"flight":"6E 2345","date":"2026-11-25"}'
```

Track it. This is what the app will do; RLS checks that it's your own row.

```bash
curl -s -X POST "$SUPABASE_URL/rest/v1/user_flights" -H "apikey: $ANON_KEY" -H "authorization: Bearer $JWT" -H "content-type: application/json" -d '{"flight_id":"<id from search>"}'
```

Run one poll cycle by hand (cron does this every 30 s in the cloud):

```bash
curl -s -X POST "$SUPABASE_URL/functions/v1/poll-flights" -H "x-cron-secret: $CRON_SECRET"
```

## Deploy to Supabase cloud

Link the project:

```bash
supabase link --project-ref <ref>
```

Apply the migrations, including pg_cron + pg_net:

```bash
supabase db push
```

Load the airports:

```bash
psql "$DB_URL" -f supabase/seed.sql
```

Set the function secrets (`.env` never goes into git):

```bash
supabase secrets set --env-file supabase/functions/.env
```

Deploy the functions:

```bash
supabase functions deploy search-flight poll-flights provider-webhook
```

Then run this once in the SQL editor. It stores the cron secrets in Vault, not in code:

```sql
select vault.create_secret('https://<ref>.supabase.co', 'project_url');
select vault.create_secret('<CRON_SECRET value>', 'cron_secret');
```

Also enable Anonymous sign-ins in Auth settings. Google sign-in comes in Step 3, and phone OTP in v0.2 (that one needs DLT).

## Going live with AeroDataBox

1. Subscribe on RapidAPI or API.Market and put the key in `ADB_API_KEY`. On API.Market, set `ADB_BASE_URL` and `ADB_KEY_HEADER` from its dashboard.
2. Set `FLIGHT_PROVIDER=aerodatabox`.
3. **Measure gate and belt coverage at DEL, BOM, BLR, HYD, MAA and CCU first**, using the free quota (about 600 units). Indian gate and belt data quality is not verified yet.
4. Only then consider `ADB_WEBHOOKS=true` (credit-billed, best-effort).

## Security notes

* The app can't read `flights` directly. It reads `flight_view`, which masks the Pro-only fields server-side.
* Users can't change their own `plan`, and `claim_due_flights` and recipients are service-role only.
* `poll-flights` is protected by `x-cron-secret`, and `provider-webhook` by a URL token compared in constant time.
* Free users can track at most 10 active flights (Pro: 50), and searches are rate-limited per user. Both protect the API bill.
