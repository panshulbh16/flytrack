# Gmail ticket import — design

Date: 2026-11-24. Status: approved in conversation, awaiting spec review. Base: `origin/main` at 6c934b6 (PRs #4–#8 merged).

## Goal

"Import a ticket" offers two clear options on iOS and Android:

1. **From a file or screenshot**: today's manual import, unchanged.
2. **From Gmail**: reads the user's mail read-only on the phone, finds flight bookings, and helps them track those flights.

New bookings are also found automatically, and the user is asked, in the app and by a phone notification, whether to track each one. Nothing is tracked without the user confirming on Add flight.

## Decisions (from the user)

| Question | Decision |
|---|---|
| Audience | Public release (needs Google restricted-scope verification). |
| Cloud reading of Gmail mail | Never. Gmail-derived text is read on the phone only. |
| Fetch mode | On tap, plus an automatic check for new mail. |
| New finds | Suggested; the user confirms. |
| Notification | Each new find also raises a phone notification asking whether to track it. |

## Rules that do not change

- Tickets are read on the phone (README, ticket import section).
- Cloud reading (`parse-ticket`) needs the user's consent and sends only extracted text, never the file. The Gmail path never calls it.

## Google constraints

- `https://www.googleapis.com/auth/gmail.readonly` is a **restricted** scope. No non-restricted Gmail scope can read bodies or attachments (`gmail.metadata` is also restricted and has no body).
- Public use needs restricted-scope verification. An app showing the "unverified app" screen is capped at 100 new users.
- **Security assessment (CASA): plan for it.** Google's pages disagree. The developer guide ties the yearly third-party assessment to storing or transmitting restricted data on servers, but the same guide and Cloud Help also say every restricted-scope app is assessed and reverified every 12 months, and none lists an on-device exemption. This design keeps all Gmail data, and everything derived from it, on the phone. Ask Google's verification team to confirm in writing whether that exempts it; until they do, plan for an assessment before launch and annual reverification.
- **Limited Use** applies to raw and derived Gmail data: use it only for the user-facing import feature; never for ads; never to train generalized AI/ML models; never transfer it except as needed for the feature; never let a human read it except with the user's affirmative agreement for specific messages, for security, or to comply with law. Support and logs must never ask for or record email content.

Sources: [Gmail API scopes](https://developers.google.com/workspace/gmail/api/auth/scopes), [Restricted scope verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification), [Restricted scopes](https://support.google.com/cloud/answer/13464325), [API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy), [Google Workspace API user data and developer policy](https://developers.google.com/workspace/workspace-api-user-data-developer-policy).

## Approaches considered

- **A. Phone calls Gmail directly (chosen).** The token stays on the phone, Gmail is called over HTTPS from the app, and the existing on-device parser reads each email. No Gmail data reaches the server. Weakness: iOS background checks are opportunistic.
- **B. Server-side Gmail sync.** Better iOS background coverage, but mail data on the server breaks the on-device rule and certainly triggers CASA. Rejected.
- **C. Forwarding address.** No Gmail scope, but mail reaches the server and it is not what was asked for. Possible later addition.

## User flow

1. **Entry.** Every "Import a ticket" button opens a chooser. On Android these are the Flights top-bar icon and the Flights empty state (both call `onImportTicket` in `FlightsRoute`). On iOS they are the Flights empty state and the Add flight toolbar item shown when the prefill is empty (`Screens.swift`); iOS has no import item in the Flights toolbar and this spec does not add one. The chooser offers **From a file or screenshot** (opens the existing import screen unchanged) and **From Gmail** (opens the new Gmail screen). It is a bottom sheet on Android and a `confirmationDialog` on iOS. "From Gmail" is hidden when the release switch is off or Google sign-in is not configured in the build.
2. **Connect.** The Gmail screen is the in-app disclosure Google requires: "HeyGate reads your booking emails (read-only Gmail access) to find tickets to import. It reads them on this phone only. Nothing from your mail is sent to HeyGate." **Connect Gmail** always shows the account picker; the account need not be the one linked to HeyGate.
3. **Scan.** The app scans for new booking emails (see Scanning), then lists every pending suggestion: flight, date, route (when known), and the email subject. This includes suggestions found by earlier scans and by the automatic check. Dismissed and added suggestions are not shown or offered again.
4. **Pick.** Tapping a suggestion opens Add flight pre-filled (flight, date, PNR, source `gmail`, suggestion id). The user confirms there.
5. **Automatic check.** After connecting, new mail is checked automatically (Auto-check switch in Settings, on by default). Each new find becomes a pending suggestion:
   - In-app: a **Found in Gmail** card on the Flights screen with **Add** and **Dismiss** per suggestion.
   - Notification, when the app is not in the foreground: "Track 6E 2341 on 3 Dec, DEL→BOM? Found in your Gmail." (without a known route: "Track 6E 2341 on 3 Dec? Found in your Gmail."). Actions: **Track** (opens Add flight pre-filled; tapping the body does the same) and **Not now** (dismisses without opening the app). Several finds in one check are grouped.
6. **Settings → Gmail.** Shows the connected address, the Auto-check switch, **Disconnect Gmail**, and "Reconnect Gmail" when access was lost.

Track opens the app instead of tracking from the notification because a flight number and date can match several legs; Add flight already resolves that.

### Suggestion lifecycle

- A suggestion is `pending`, `dismissed` or `added`.
- **Add** (card), **Track** (notification, or tapping its body) and tapping a row on the Gmail screen open Add flight with the suggestion id: Android `AddFlight.prefillGmailSuggestionId: String?`, iOS `AddFlightPrefill.gmailSuggestionId: String?`. Track also cancels its notification at once (Android action buttons do not auto-cancel).
- The suggestion stays `pending` until Add flight tracks the flight successfully; then Add flight marks it `added` and cancels its notification. Backing out of Add flight leaves it `pending` and on the card.
- **Dismiss** (card) and **Not now** (notification) mark it `dismissed` and cancel its notification.
- The card and the Gmail screen show only `pending` suggestions dated today or later that do not match a tracked flight (same flight number and date), checked each time they are shown.
- Each scan and each app-open check deletes stored suggestions of any status dated before today, marks `added` any pending suggestion that now matches a tracked flight (tracked another way), and cancels the notifications of both.

## Scanning

- **Query.** Gmail `users.messages.list` with
  `q = subject:(ticket OR e-ticket OR itinerary OR booking OR PNR OR confirmation OR "boarding pass") -subject:(cancel OR cancelled OR canceled OR cancellation OR refund OR refunded)`
  followed by ` newer_than:180d` until a scan has completed, then ` after:<lastCheckEpochSeconds − 3600>` (one hour of overlap, computed before building the string). Gmail matches whole words, so each variant is listed.
- **Paging and cap.** Page through `messages.list` (`maxResults=100`, following `nextPageToken`); do not rely on list order. Skip ids already seen without fetching or counting them. Read at most 50 unseen messages per scan.
- **Last check.** Record the scan's start time and save it as `lastCheck` only when the scan completes: the listing was paged to the end and every unseen message was read or skipped as undecodable. A scan that stops early (the 50-message cap, 429/5xx, offline, iOS background time running out) leaves `lastCheck` unchanged; seen ids stop the next scan re-reading what was read.
- **Reading.** `users.messages.get?format=full`. Read both the `text/plain` part and the `text/html` part (tags stripped, entities decoded) when both are present, plain text first, joined by a line break, because a plain part can be only a "View in browser" stub; otherwise read whichever one exists. Bodies are base64url. A leg found in both parts becomes one suggestion. Skip the message if its subject contains `cancel` or `refund` (case-insensitive substring; a backstop to the query). Run `parseTicketText` on the body. If it finds no flight and the message has a PDF attachment of 5 MB or less, fetch it (`messages.attachments.get`), write it to a temp file, read it with the existing on-device PDF reader (Android `TicketTextExtractor`, iOS `TicketTextExtracting`), and delete the file.
- **Filter.** Drop legs with no date (they cannot be shown to be upcoming). Keep legs dated today or later by the phone's date. A leg with no route is kept with a blank route. Drop legs matching a tracked flight (same flight number and date) or an existing suggestion of any status. Merge duplicates (same flight and date from several emails) into one, keeping the newest email's PNR and subject. Each leg of a round trip or multi-leg booking is its own suggestion.
- **Seen mail.** Every message read, or skipped as undecodable or cancelled, is marked seen.
- **Throttle.** The app-open check runs at most once an hour. A scan from the Gmail screen always runs.

## Architecture

Pure logic is unit-tested without a device: on iOS in `ios/FlyTrackCore` (builds on Linux), on Android as JVM tests. Parsing reuses `parseTicketText` and the PDF readers; no new parser.

### Pure logic (both apps)

| Unit | Does |
|---|---|
| `GmailQuery` | Builds the exact search string for first and later scans. |
| `GmailMessageText` | Walks a Gmail message payload, picks the body part, base64url-decodes, strips HTML, finds a PDF attachment id. |
| `GmailSuggestions` | Turns parsed tickets into suggestions (filter, merge, cancel backstop) and applies the lifecycle cleanup (past, tracked elsewhere). |
| `GmailScanState` | Gmail screen states: not connected, scanning, results, no pending suggestions, stopped early with more to read, offline, needs reconnect. |

### Android (`feature/gmailimport/`)

- **`GmailAuth`**: Google Identity `AuthorizationClient` from `com.google.android.gms:play-services-auth` **21.5.0 or later**, added as its own entry in `android/gradle/libs.versions.toml` and declared with `implementation`. (The 21.1.1 that `credentials-play-services-auth` 1.6.0 pulls in has no `revokeAccess`/`clearToken`, added in 21.4.0, and no `setPrompt`, added in 21.5.0.)
  - Connect builds the `AuthorizationRequest` for `gmail.readonly` with `setPrompt(SELECT_ACCOUNT)`, so the user always picks the account; otherwise the account linked to HeyGate through Credential Manager is used silently.
  - After consent, read the address with `users.getProfile` (`emailAddress`) and store it in `SettingsStore`.
  - Every later `authorize()` (Gmail screen, app-open check, worker) uses `setAccount(Account(storedEmail, "com.google"))`. It returns a token silently; the app stores no token. A `PendingIntent` resolution means user action is needed: in the foreground show it; in the background mark "needs reconnect".
  - Disconnect calls `revokeAccess(RevokeAccessRequest)` for the stored account and `gmail.readonly`.
- **`GmailApi`**: four GET calls (`users.getProfile`, `messages.list`, `messages.get`, `messages.attachments.get`) on its **own** Ktor `HttpClient(OkHttp)` (`ktor-client-okhttp` is already a dependency; tests use `ktor-client-mock`). It is separate from `SupabaseClientProvider`'s client, so Gmail works without backend keys and sends only `Authorization: Bearer <Google token>`.
- **`GmailScanner`**: runs a scan end to end and writes suggestions; used by the Gmail screen, the app-open check and the worker.
- **`GmailCheckWorker`**: WorkManager periodic work every 6 hours with a network constraint; enqueued on connect, cancelled on disconnect or when Auto-check is off. Posts notifications for new suggestions.
- **`GmailSuggestionNotifier`**: channel `ticket_suggestions` ("Found in your email", default importance). Track is an activity `PendingIntent` carrying the suggestion id; `LaunchRequest` gains `OpenGmailSuggestion(id)` (id validated like flight ids). Not now is a broadcast `PendingIntent` to a non-exported receiver that marks the suggestion dismissed. Uses the existing Android 13+ notification permission; without it only the card shows.
- **Storage**: Room tables `gmail_suggestions` (id, message id, flight, date, from and to nullable, pnr, subject, status, found at) and `gmail_seen_messages` (message id). `FlyTrackDatabase` 1 → 2 with a migration. These tables are local-only state, not a cache of server data: update the `FlyTrackDatabase` KDoc and the `DataModule` comment, and replace `.fallbackToDestructiveMigration(dropAllTables = true)` with `.fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)` (or remove it), so a later schema change without a migration fails loudly instead of losing dismissed suggestions and seen ids. `SettingsStore` gains Gmail account email, auto-check flag and last check time.
- **UI**: import chooser sheet, `GmailImportRoute` + view model, Found in Gmail card in `FlightsRoute`, Gmail section in `SettingsRoute`, `AddFlight.prefillGmailSuggestionId`.

### iOS

- **Google configuration helper**: Info.plist has no `GIDClientID`; the ids live in `GoogleIOSClientID`/`GoogleWebClientID`. Factor the guard on `AppConfig.isGoogleConfigured` plus `GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID:serverClientID:)` out of `GoogleLinker.token()` into one helper in `Services.swift`, called before any `signIn` or `restorePreviousSignIn` by both `GoogleLinker` and `GmailAuth`. Without it, connecting Gmail before linking Google in the same launch crashes with "No active configuration".
- **`GmailAuth`**: the existing GoogleSignIn SDK. Connect calls `signIn(withPresenting:hint:additionalScopes:)` with `gmail.readonly`; later calls use `restorePreviousSignIn` and `refreshTokensIfNeeded`. The connected address is `GIDGoogleUser.profile?.email` (or `users.getProfile` when profile is nil), stored in settings. Known limit: GoogleSignIn keeps one current user, so linking a *different* Google account to HeyGate replaces the Gmail user; the next Gmail call sees the scope missing or a different email and the app shows "Reconnect Gmail". Disconnect calls `disconnect()` only when the current user's email is the stored Gmail address.
- **`GmailAPIClient`**: `messages.list`, `messages.get`, `messages.attachments.get` (and `users.getProfile` as a fallback) on `URLSession`.
- **`GmailScanner`**: the orchestration lives in `FlyTrackCore` behind small protocols (Gmail API, token provider, text extractor, suggestion store) so it is unit-tested with fakes like Android's. The app supplies the real implementations.
- **Notification delegate at launch**: move `UNUserNotificationCenter.current().delegate = PushBridge.shared` out of `LivePush.start()` into `AppDelegate.application(_:didFinishLaunchingWithOptions:)`, set unconditionally, and register the `GMAIL_SUGGESTION` category there with `setNotificationCategories`. Today the delegate is set only when Firebase is configured and only after `AppShell.bootstrap()`, which does not run on a background launch.
- **Notifications**: local `UNNotificationRequest`s with category `GMAIL_SUGGESTION` (Track: `.foreground`; Not now: no options, runs in the background), `suggestion_id` in userInfo, thread id grouping them. `PushBridge` gains `onGmailSuggestion: ((String, Bool) -> Void)?` (id, dismiss), set by AppShell next to `onFlight`. In `didReceive`, before the `flight_id` guard, a `GMAIL_SUGGESTION` response with a valid id calls it: dismiss for Not now, otherwise Track. Track loads the `GmailSuggestionRecord` and appends `Route.add(AddFlightPrefill(..., source: .gmail, gmailSuggestionId:))`. Not now marks the record dismissed directly in storage, since the UI may not be running. `willPresent` returns no options for `GMAIL_SUGGESTION`; the card shows instead.
- **Background**: `BGAppRefreshTask` with identifier `app.heygate.gmail-check`. Register its handler in `AppDelegate.application(_:didFinishLaunchingWithOptions:)` (or with the SwiftUI `.backgroundTask(.appRefresh(...))` scene modifier), never from `AppShell.bootstrap()`: registering after launch finishes crashes, and bootstrap does not run on a background launch. Submit a `BGAppRefreshTaskRequest` on app open and at the end of each run. The expiration handler stops the scan the same way as hitting the cap. In `project.yml` `info.properties`, add `fetch` to `UIBackgroundModes` next to `remote-notification`, and add `BGTaskSchedulerPermittedIdentifiers`.
- **Storage**: SwiftData `GmailSuggestionRecord` and `GmailSeenMessageRecord` added to the existing `ModelContainer`. The settings store gains the same three values as Android.
- **UI**: chooser `confirmationDialog`, `GmailImportScreen`, Found in Gmail card on the Flights screen, Gmail section in Settings, `AddFlightPrefill.gmailSuggestionId`.

### Server

- Migration `supabase/migrations/20261124000007_gmail_source.sql`:
  `alter table public.user_flights drop constraint user_flights_source_check, add constraint user_flights_source_check check (source in ('manual','pdf','share','screenshot','gmail'));`
  (the init check is unnamed, so this uses Postgres's generated name).
- Add the file to the hardcoded list in `scripts/test-sql.sh` (before `seed.sql`) and add a SQL test inserting a `user_flights` row with source `'gmail'`.
- Both apps' `TrackSource` gain `gmail`. `fromWire` already maps unknown values to `manual`, so older app versions read these rows as manual.
- Nothing else on the server validates `source`, and no mail data reaches the server.

## Errors

| Situation | Behaviour |
|---|---|
| 401 | Get a fresh token once and retry (Android: `clearToken`, then `authorize()`; iOS: `refreshTokensIfNeeded`). If the retry gets 401, or a token needs user action, treat as access lost. |
| Access lost: revoked, scope missing, or 403 with reason `insufficientPermissions`/`forbidden` | "Reconnect Gmail" on the Gmail screen and in Settings; background checks stop until reconnected. |
| 429, 403 with reason `rateLimitExceeded`/`userRateLimitExceeded`/`dailyLimitExceeded`, or 5xx | Stop the scan, keep what was found, leave `lastCheck` unchanged; continue next run. |
| Offline | Offline notice; leave `lastCheck` unchanged; background retries next run. |
| An email cannot be decoded or parsed | Skip it and mark it seen. |
| No pending suggestions after a scan | "No upcoming flights in your recent booking emails" with **Import manually** and **Type it instead**. |
| No pending suggestions after a scan from the Gmail screen that stopped early (50-email cap reached) | "Some booking emails are still unread. Scan again to keep looking." with **Scan again**; never the "No upcoming flights" message. |
| Notification permission denied | In-app card only. |
| Google sign-in not configured in the build | "From Gmail" hidden. |

## Privacy

- Gmail-derived text never leaves the phone. The Gmail code path has no dependency on `CloudTicketParser`/`CloudTicketParsing`.
- Stored on the phone only: seen message ids, and per suggestion the flight, date, route, PNR and email subject. No email bodies are stored. Email content is never logged.
- Disconnect revokes access (as described per platform), deletes suggestions, seen ids and Gmail settings, cancels background work, and removes pending Gmail notifications.
- Signing out of HeyGate, or switching to a different HeyGate user, clears the same local state: hook it beside Android `RoomLocalUserData.clear()` and iOS `LocalCache.wipeUserData`, and also cancel the WorkManager job or BGTask request and remove pending Gmail notifications. Android also revokes the stored Gmail account. iOS revokes only when the current GoogleSignIn user is still the stored Gmail address; after a `GoogleLinker` switch it only clears local state (the user can remove the grant at myaccount.google.com).
- The only Gmail-derived values that reach the server are what the user confirms on Add flight (flight, date, PNR, source `gmail`), the same as any manual add.

## Testing

- **Unit (both apps)**: `GmailQuery` (the exact string), `GmailMessageText` with fixture payloads (synthetic IndiGo, Air India and Akasa emails: text-only, HTML-only, multipart, PDF-only), `GmailSuggestions` (filter, merge, undated legs dropped, cancel backstop, past/tracked cleanup), `GmailScanState`, and the `lastCheck` rule (advances only on a completed scan).
- **Android**: `GmailImportViewModel` and `GmailScanner` with a fake `GmailApi` (ktor-client-mock) and fake auth; 401 retry; `LaunchRequest` parsing for the suggestion intent. The Room 1 → 2 migration test needs instrumentation: add `src/androidTest`, `androidTestImplementation(libs.room.testing)` plus the androidx.test runner and ext-junit, `MigrationTestHelper` over `app/schemas` (add the schemas folder to androidTest assets); it runs with `connectedAndroidTest`, not in the JVM tests.
- **iOS**: `swift test` in `FlyTrackCore` covers the pure logic and `GmailScanner` with fakes. There is no app test target, so `xcodebuild test` only re-runs those and checks the app compiles. `GmailAPIClient`, `GmailAuth`, the BGTask and notifications are covered by the manual test.
- **SQL**: `scripts/test-sql.sh` with the new migration and the gmail-source insert test.
- **Manual**: a test Gmail account added as an OAuth test user, on an Android device and the iOS simulator: connect (account picker shown), scan, add, back out of Add flight (suggestion stays), background find + notification, Track, Not now, disconnect, sign out of HeyGate.

## Release

- **Release switch.** `GMAIL_IMPORT_ENABLED` (Android `BuildConfig` field, iOS xcconfig → Info.plist): on in debug, off in release until Google verification passes.
- **Google Cloud (owner's checklist).** Add `gmail.readonly` to the OAuth consent screen and add test users. On the app's own site, publish a privacy policy with the affirmative statement: "use of data from Google Workspace APIs adheres to the Google API Services User Data Policy, including the Limited Use requirements". Verify the home page domain. Record a demo video of connect → scan → add showing the consent screen. Submit for restricted-scope verification, stating that all Gmail data is processed on the device only and asking whether that exempts the app from CASA. If Google requires it, complete the CASA assessment before launch and budget for annual reverification.
- **Store disclosures.** Play Data safety and `PrivacyInfo.xcprivacy`: email content is processed on device and not collected or shared.

## Out of scope

- Outlook or other mail providers; the forwarding address (approach C).
- Tracking directly from the notification without Add flight.
- Reading Gmail on the server, or cloud reading of Gmail-derived text.
