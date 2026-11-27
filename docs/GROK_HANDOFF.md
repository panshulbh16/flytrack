# Handoff prompt for Grok (or any AI assistant)

Copy everything below the line into Grok.

---

You are taking over development of **FlyTrack**, an India-first live flight tracker in the style of Flighty. The iOS app is named **HeyGate**. The owner is Panshul Bharadwaj. Talk to him in simple Hinglish or English, ask before big decisions, and never hardcode API keys or secrets.

## Repo and current state
* **GitHub:** `github.com/panshulbh16/flytrack`.
* **Branches:** `main` has P1 + P2. **All P3 + P4 work is on branch `feat/p4`, in open PR #13** ("Flighty parity P3 + P4"). Merge PR #13 first (all tests were green), or work on `feat/p4`.
* **Docs, read these first:**
  * `FLYTRACK_RULES.md`
  * `README.md`
  * `docs/DEPLOYMENT.md`
  * the contracts, in order: `docs/P1_CONTRACTS.md`, `docs/P2_CONTRACTS.md`, `docs/P2_WAVE2_CONTRACTS.md`, `docs/P3_CONTRACTS.md`, `docs/P4_CONTRACTS.md`. Each has a **Status** section with open items.
  * `docs/FLIGHTY_PARITY.md` (the feature matrix)
  * `docs/design-brief.md` (UI tokens)
* **Stack:**
  * Supabase: Postgres + Deno Edge Functions + pg_cron.
  * Android: Kotlin/Compose, single `:app` module plus a `:wear` module.
  * iOS: SwiftUI, with logic in `ios/FlyTrackCore`; XcodeGen `ios/project.yml`.
  * Flight data: AeroDataBox, behind a provider interface. Push: FCM. Billing: RevenueCat.
* **Prices:** ₹4,999/yr, ₹499/mo, ₹99 trip pass (7 days), plus gift products of the same value.
* **Live Supabase:** project `ovilmvrxgqdaapafqqix` (Singapore).
  * It has migrations **01–20** (P1 + P2) and 12 Edge Functions.
  * **Migrations 21–35 and the P3/P4 functions are NOT deployed yet.** Migrations 21–33 were rehearsed on live inside a rolled-back transaction and applied cleanly. 35 was not rehearsed.
  * Flight data is still `FLIGHT_PROVIDER=mock`, and pushes are `PUSH_DRY_RUN=true`.

## Test commands (all were green at handoff)

Backend tests (300 pass). Then type-check every function with `deno check <fn>/index.ts`:

```bash
cd supabase/functions && deno test
```

SQL tests (needs a local Postgres; a shim fakes Supabase's `auth`):

```bash
scripts/test-sql.sh
```

Android tests and builds (`:app` 1082 pass, `:wear` 17 pass). This needs `android/local.properties` with `sdk.dir`:

```bash
cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :wear:testDebugUnitTest :wear:assembleDebug
```

Terminal-map script tests (20 pass):

```bash
node --test scripts/build-terminal-maps.test.mjs
```

iOS tests (**never compiled yet**). Then do an Xcode build of the app, widget, watch and share-extension targets via `xcodegen`:

```bash
cd ios/FlyTrackCore && swift test
```

## Leftover tasks, in order

1. **Compile iOS on a Mac.**
   * Run `xcodegen` in `ios/`, then `swift test` in `ios/FlyTrackCore`, then build every target.
   * Fix compile errors. The code was written without a Swift toolchain, so expect some.
   * Register the App Groups `group.app.heygate.flytrack` and `group.app.flytrack`, plus the widget and watch bundle ids, in the Apple developer account.
2. **Deploy P3 + P4 to live Supabase**, after the owner confirms.
   * Apply migrations `20261126000021` … `20261127000035` in one transaction. Rehearse first with `begin; …; rollback;` through the Management API `POST /v1/projects/{ref}/database/query`, or use `supabase db push`.
   * Insert each version into `supabase_migrations.schema_migrations`.
   * Then run `supabase functions deploy --remote=ovilmvrxgqdaapafqqix --use-api`.
   * Smoke-test as an anonymous user (see `docs/DEPLOYMENT.md` for the checklist used for P1/P2) and update `DEPLOYMENT.md`.
3. **Real flight data:** set `ADB_API_KEY` (AeroDataBox via RapidAPI or API.Market) and `FLIGHT_PROVIDER=aerodatabox`.
   * Measure gate, belt and terminal coverage for DEL, BOM, BLR, HYD, MAA and CCU with `scripts/coverage-report.sql` after a few days.
   * Verify the aircraft lookup and the airport-departures query parameters against the real API.
4. **Push:**
   * Create the Firebase project.
   * Put `google-services.json` in `android/app/` (it is gitignored).
   * Set `FCM_SERVICE_ACCOUNT_JSON`, then remove `PUSH_DRY_RUN`.
5. **Billing:**
   * Create these products in Play Console and App Store Connect:
     * `heygate_pro_yearly` ₹4,999
     * `heygate_pro_monthly` ₹499
     * `heygate_trip_pass` ₹99, consumable
     * `heygate_gift_year` ₹4,999, consumable
     * `heygate_gift_trip` ₹99, consumable
   * In RevenueCat, create entitlement `pro`. Attach only the yearly and monthly products to it, **not** the trip or gift products. Create offering `default`.
   * Set `REVENUECAT_WEBHOOK_AUTH` (`openssl rand -hex 32`) and `REVENUECAT_SECRET_API_KEY`, and point the webhook at `/functions/v1/revenuecat-webhook`.
   * Put the SDK keys in `android/local.properties` (`REVENUECAT_ANDROID_KEY`) and the iOS xcconfig (`REVENUECAT_IOS_KEY`).
   * Decide whether sandbox/TestFlight purchases should grant Pro on production. They currently do.
6. **Terminal maps:** on a machine with normal internet, run `node scripts/build-terminal-maps.mjs`.
   * It fetches OpenStreetMap data from overpass-api.de for DEL BOM BLR HYD MAA CCU COK AMD.
   * Check the counts, then commit `data/terminal-maps/*.geojson` and the copies in the Android assets and iOS resources.
   * Keep the "© OpenStreetMap contributors (ODbL)" attribution.
7. **Public web pages:** host `web/share/`, `web/airports/` and `web/report/` (GitHub Pages or Cloudflare Pages).
   * Set `SUPABASE_URL` in each `config.js`.
   * Set `SHARE_BASE_URL` in both apps (default `https://heygate.app/f/`).
8. **Email forwarding:** pick an inbound email provider (Postmark, SendGrid or Cloudflare Email Routing).
   * Point MX for `in.heygate.app` at it.
   * Write the small adapter to the body shape in `P2_WAVE2_CONTRACTS.md` §2, and set `INBOUND_EMAIL_SECRET`.
9. **Optional integrations:**
   * **TripIt:** register a fixed callback URL in the TripIt developer settings. This closes the residual CSRF risk in `P4_CONTRACTS.md` §8. Then set `TRIPIT_CONSUMER_KEY`/`SECRET`.
   * **Radar:** buy a commercial position-feed licence (OpenSky commercial or ADS-B Exchange). Then set `RADAR_FEED`, its credentials and `RADAR_FEED_LICENSED=true`.
   * **Cloud ticket parsing:** set `ANTHROPIC_API_KEY`.
   * **Google sign-in:** configure it in Supabase Auth and put `GOOGLE_WEB_CLIENT_ID` in `local.properties`.
10. **Device testing:**
    * Android on real Xiaomi, Vivo, Oppo and Samsung phones: alerts arriving with the app killed, the live notification, the widget, the map and the Wear tile.
    * iOS on an iPhone: the Live Activity, widgets and the Watch.
11. **Verify the seed data:**
    * The minimum connection times in migration 25 (all `verified = false`).
    * The check-in rules for AI, IX, QP and SG (only IndiGo is verified).
12. **Known open items** (see each contract's Status section):
    * friend-invite guessing has no rate limit;
    * anyone who knows a user's forwarding address can add flights to their account;
    * iOS Live Activity is Pro-only (a product decision);
    * the `connection_alert_candidates` query needs narrowing at scale.

## Rules to keep
* Instant, correct alerts are the core product. Never break the dedupe or alert-state logic in `supabase/functions/_shared/diff.ts` and `engine.ts` without tests.
* The app never calls the flight API. Only Edge Functions do, one poll per unique flight.
* Every backend change needs Deno and SQL tests. Never edit an applied migration; add a new numbered one.
* Keep Android, iOS and backend names in sync with the contract docs, and update a contract before changing a name.
* Every user-facing string needs English plus Hindi (`values-hi`, the iOS `hi` catalog). Alerts are in en, hi and hinglish.
* Commit to a feature branch and open a PR. Don't push straight to `main`.
