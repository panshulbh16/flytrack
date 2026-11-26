# Live backend (Supabase)

Set up on 23 Nov 2026.

| | |
|---|---|
| Project | `Flytrack`, ref `ovilmvrxgqdaapafqqix` |
| Region | `ap-southeast-1` (Singapore). This replaced the first project in Seoul; Singapore is closer to India. Mumbai (`ap-south-1`) would be closer still. |
| URL | `https://ovilmvrxgqdaapafqqix.supabase.co` |
| Dashboard | https://supabase.com/dashboard/project/ovilmvrxgqdaapafqqix |

## What is deployed (updated 26 Nov 2026: P1 + P2)

**Database:** migrations `20261122000001` to `20261126000020`, all recorded in `supabase_migrations.schema_migrations`.
* Migration `20261124000006` (live_activities) had been applied by hand with a token check Postgres can't evaluate (`{16,512}`), so every Live Activity registration failed. On 26 Nov it was reconciled in place: the check and `register_live_activity` were replaced with the fixed versions and the migration was recorded.
* 07–20 were applied in one transaction, after a full dry run on the live database that was rolled back.
* 7,917 airports and 5 `checkin_rules` are loaded.

**Scheduler (pg_cron)**

| Job | Schedule | What it does |
|---|---|---|
| `flytrack-poll` | every 30 s | `poll-flights`, only when work is due (flight poll, check-in reminder or fog alert) |
| `flytrack-airport-weather` | at :05, :20, :35 and :50 | `refresh-airport-weather`; METAR from aviationweather.gov, verified working from live |
| `flytrack-housekeeping` | nightly | housekeeping |

**Vault:** `project_url` and `cron_secret`.

**Edge Functions (12)**

| JWT required | No JWT (each checks its own secret or is public) |
|---|---|
| `search-flight`, `parse-ticket`, `send-test-push`, `sync-entitlement`, `ack-push` | `poll-flights`, `provider-webhook`, `refresh-airport-weather` (cron secret); `revenuecat-webhook` (Authorization secret, refuses everything until it is set); `inbound-email` (`x-inbound-secret`, refuses until set); `shared-flight` and `calendar-feed` (public, token in URL) |

**Auth:** anonymous sign-ins are on. The Google provider is not configured yet.

**Function secrets set:** `FLIGHT_PROVIDER=mock`, `CRON_SECRET`, `WEBHOOK_SECRET`, `PUSH_DRY_RUN=true`, `ADB_WEBHOOKS=false`.

## Verified on the live project (26 Nov 2026, 20/20 smoke checks)

All of these passed in one run as a fresh anonymous user:
* anonymous sign-up → `search-flight` (6E 2345, mock) → track (RLS)
* the first tracked flight became the free Pro trial
* `flight_view` read
* `create_flight_share` → public `shared-flight` JSON with no PII; a bad token gets 404
* `checkin_rules` (5 rows)
* `get_airport_health('DEL')`
* `my_passport`
* `my_calendar_url` → `calendar-feed` ICS
* `create_friend_invite`
* `my_forwarding_address`
* `flight_forecast`
* `sync-entitlement` (free, trial flight id returned)
* `revenuecat-webhook` rejects a bad Authorization
* users are refused direct access to `flights` and to `claim_due_flights`

Also checked:
* `poll-flights` returned 200 from cron and sent a check-in reminder (dry run).
* The weather refresh stored 13 metro airports.

Test data left on live: one anonymous test user tracking mock flight 6E 2345 (tomorrow), with a share link, a friend invite and a calendar token.

## To go live (in this order)

1. **Flight data:** set `ADB_API_KEY` (RapidAPI, AeroDataBox) and change `FLIGHT_PROVIDER` to `aerodatabox`.
2. **Push:**
   * Create a Firebase project.
   * Put `google-services.json` in `android/app/` (gitignored).
   * Set the `FCM_SERVICE_ACCOUNT_JSON` secret, then **remove** `PUSH_DRY_RUN`.
3. **Payments:**
   * Create the products in Play Console and App Store Connect: `heygate_pro_yearly` (₹4,999), `heygate_pro_monthly` (₹499), `heygate_trip_pass` (₹99, consumable).
   * In RevenueCat, set up entitlement `pro` (without the trip pass) and offering `default`.
   * Set the secrets `REVENUECAT_WEBHOOK_AUTH` (`openssl rand -hex 32`) and `REVENUECAT_SECRET_API_KEY`.
   * Point the RevenueCat webhook at `/functions/v1/revenuecat-webhook`.
   * Put `REVENUECAT_ANDROID_KEY` in `local.properties` and `REVENUECAT_IOS_KEY` in the xcconfig.
4. **Share page:** deploy `web/share/` (`config.js` → `SUPABASE_URL`) to the `SHARE_BASE_URL` host (default `https://heygate.app/f/`). Set `SHARE_BASE_URL` in both apps.
5. **Email forwarding:**
   * Choose an inbound email provider and point MX for `in.heygate.app` at it.
   * Set `INBOUND_EMAIL_SECRET` and write the provider adapter (P2 wave 2 contract, section 2).
6. **Cloud ticket parsing:** set `ANTHROPIC_API_KEY`.
7. **Google sign-in:**
   * Enable the Google provider in Supabase Auth with a Web client ID and secret.
   * Put the Web client ID in `GOOGLE_WEB_CLIENT_ID` in `android/local.properties`.

Secrets are set under Dashboard → Edge Functions → Secrets, or with:

```bash
supabase secrets set --project-ref ovilmvrxgqdaapafqqix NAME=value
```

## App configuration (never committed)

Android, `android/local.properties`:

```properties
SUPABASE_URL=https://ovilmvrxgqdaapafqqix.supabase.co
SUPABASE_ANON_KEY=<anon key from Dashboard → Settings → API Keys>
GOOGLE_WEB_CLIENT_ID=
```

iOS: the same two values in `ios/Config/Secrets.xcconfig`.

## Redeploy

```bash
supabase db push --project-ref ovilmvrxgqdaapafqqix          # migrations recorded in schema_migrations
supabase functions deploy --remote=ovilmvrxgqdaapafqqix --use-api   # no Docker needed
```
