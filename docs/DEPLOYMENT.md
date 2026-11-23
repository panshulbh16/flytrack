# Live backend (Supabase)

Set up on 23 Nov 2026.

| | |
|---|---|
| Project | `Flytrack`, ref `ovilmvrxgqdaapafqqix` |
| Region | `ap-southeast-1` (Singapore). This replaced the first project in Seoul; Singapore is closer to India. Mumbai (`ap-south-1`) would be closer still. |
| URL | `https://ovilmvrxgqdaapafqqix.supabase.co` |
| Dashboard | https://supabase.com/dashboard/project/ovilmvrxgqdaapafqqix |

## What is deployed

- **Database:** migrations `20261122000001` to `20261122000004`, recorded in `supabase_migrations.schema_migrations`, so `supabase db push` only applies newer ones. 7,917 airports from `supabase/seed.sql`.
- **Scheduler:** `pg_cron` job `flytrack-poll` runs every 30 s and calls `poll-flights` through `pg_net` only when a flight is due. `flytrack-housekeeping` runs nightly.
- **Vault:** `project_url` and `cron_secret` (same value as the `CRON_SECRET` function secret).
- **Edge Functions:** `search-flight` and `parse-ticket` (JWT required), `poll-flights` and `provider-webhook` (no JWT; they check their own secrets).
- **Auth:** anonymous sign-ins on. Google provider not configured yet.
- **Function secrets set:** `FLIGHT_PROVIDER=mock`, `CRON_SECRET`, `WEBHOOK_SECRET`, `PUSH_DRY_RUN=true`, `ADB_WEBHOOKS=false`.

## Verified on the live project

- Anonymous sign-up → `search-flight` (`6E 2345`) → insert into `user_flights` → read `flight_view`.
- `pg_cron` → `pg_net` → `poll-flights` returned `200 {"polled":1,"errors":0}` (725 ms) and scheduled the next poll 90 minutes later.
- `parse-ticket` returns `503 not_configured` until `ANTHROPIC_API_KEY` is set.

## To go live (in this order)

1. **Flight data:** set `ADB_API_KEY` (RapidAPI, AeroDataBox) and change `FLIGHT_PROVIDER` to `aerodatabox`.
2. **Push:**
   - Create a Firebase project.
   - Put `google-services.json` in `android/app/` (gitignored) and set the `FCM_SERVICE_ACCOUNT_JSON` secret (service-account JSON on one line).
   - Then **remove** `PUSH_DRY_RUN`.
3. **Cloud ticket parsing:** set `ANTHROPIC_API_KEY`.
4. **Google sign-in:**
   - Enable the Google provider in Supabase Auth with a Web client ID and secret.
   - Put the Web client ID in `GOOGLE_WEB_CLIENT_ID` in `android/local.properties`.

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
supabase db push --project-ref ovilmvrxgqdaapafqqix
supabase functions deploy --project-ref ovilmvrxgqdaapafqqix
```
