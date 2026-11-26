# HeyGate for iOS

Working name HeyGate by Roamly. This is the iOS app for FlyTrack. Behaviour matches the Android app in `android/ARCHITECTURE.md`: anonymous sign-in, Google linking, add-flight, the flight list and detail, settings, ticket import, and a push tap that opens the flight.

The Mac this was written for is macOS 27.2.1 with Xcode 27.2 (build 27B52). That Xcode has the iOS 27 SDK and simulator runtimes 26.5 and 27.2. There is no Xcode on the Linux machine that produced this tree, so the app target was not compiled here. `ios/FlyTrackCore` was.

## Deployment target

| Setting | Value |
|---|---|
| SDK | iOS 27, the SDK that ships with Xcode 27.2 |
| Deployment target | iOS 26.0 |
| Swift language mode | 6 |
| Xcode project format | `xcode27_0` (pbxproj, generated) |
| Scheme | `FlyTrack` |

iOS 26.0 is the floor so the installed 26.5 simulator can launch the app. A deployment target of 27 would refuse that runtime. SwiftData, Observation, Photos-less document import, and time-sensitive notifications are all available on iOS 26. The project does not call any API that exists only in the iOS 27 SDK.

## Layout

```
ios/
  project.yml                 XcodeGen spec. The .xcodeproj is generated and gitignored.
  Config/Shared.xcconfig      committed defaults, optional #include of Secrets.xcconfig
  Config/Secrets.xcconfig.example
  FlyTrackCore/               Swift package. swift build / swift test on Linux.
  FlyTrack/                   SwiftUI app, SwiftData cache, supabase-swift, PDFKit, Vision, Firebase
  ShareExtension/             share sheet for PDF, images and text
  fastlane/Fastfile           archive lane. It does not upload.
  ARCHITECTURE.md             this file
```

`FlyTrackCore` holds models, the flight-number parser, the ticket parser, time-zone formatting, push payload mapping, launch routing, and the view-model state machines. Views in `FlyTrack/` are stateless aside from holding those models. The UI reads SwiftData. Network results are written into SwiftData and the models reload, the same way the Android UI reads Room.

Packages pinned in `project.yml`:

| Package | Version |
|---|---|
| supabase-swift | 2.55.7 |
| firebase-ios-sdk | 13.2.0 (FirebaseCore, FirebaseMessaging) |
| GoogleSignIn-iOS | 10.1.0 |
| purchases-ios-spm (RevenueCat) | 5.102.3 |

## P1 (docs/P1_CONTRACTS.md)

| Feature | Core (tested on Linux) | App |
|---|---|---|
| Billing | `Billing.swift`: contract names, `EntitlementRules` (server profile + CustomerInfo, trip pass = latest `heygate_trip_pass` + 7 days), `ProService`, `PaywallModel`, `PlanPricing` | `Billing.swift`: `RevenueCatBilling` (purchases-ios 5.x), `ProCenter`; `PaywallScreen.swift` |
| Trial flight | `UserProfile.trialFlightId`, `ProState.access(for:)`; the detail model refreshes the profile once while no trial is recorded (the server trigger grants it on the first add) | "Pro free for this flight / Is flight pe Pro free" badge on the detail screen; "Show on Lock Screen" (Live Activity) needs Pro or the trial flight |
| Share | `Share.swift`: `shareURL`, `shareMessage`, `whatsAppSendURL` | `LiveFlightSharing` (`create_flight_share`), WhatsApp when installed, else `UIActivityViewController` |
| Check-in | `Checkin.swift`: `checkinWindow`, `checkinCard`, `safeCheckinURL`, `notificationTap` | `LiveCheckinRules` (table cached 12 h in UserDefaults), card on the detail screen, `checkin_url` tap opens the airline page |
| Scan source | `TrackSource.scan` | `ScanScreen` adds with `source = scan` |
| Booking editor | `FlightDetailScreenModel` editor with cabin | sheet on the detail screen; PATCH writes `pnr`, `seat`, `cabin` (null clears) |

Billing setup:

* RevenueCat app user id = Supabase user id. `ProCenter.identifyAndReload()` runs at launch and on every auth user change: the first call configures the SDK with that id, later calls `logIn`.
* Purchase and restore end with `POST sync-entitlement` and a profile refresh. Pro shows immediately from CustomerInfo (optimistic) and is marked "Confirming…" until the profile agrees.
* `REVENUECAT_IOS_KEY` in `Secrets.xcconfig` (the public `appl_…` key) reaches Info.plist as `RevenueCatAPIKey`. Blank or a placeholder: `UnavailableBilling`, the paywall says "Pro coming soon", nothing crashes.
* App Store Connect: auto-renewing `heygate_pro_yearly` (₹4,999) and `heygate_pro_monthly` (₹499) in one subscription group, consumable `heygate_trip_pass` (₹99). Prices live only in App Store Connect; the app shows the offering's `localizedPriceString`.
* RevenueCat: offering `default` with packages `$rc_annual`, `$rc_monthly` and custom `trip_pass`. Attach only the two subscriptions to entitlement `pro`.
* Add the In-App Purchase capability on the HeyGate target before archiving.

`SHARE_BASE_URL` (Info.plist `ShareBaseURL`) defaults to `https://heygate.app/f/`. In an xcconfig `//` starts a comment, so URLs are written `https:/$()/host/path`; a value cut short falls back to the default. `LSApplicationQueriesSchemes` lists `whatsapp` so `canOpenURL` can see WhatsApp.

## P2 (docs/P2_CONTRACTS.md, wave 1)

| Feature | Core (XCTest in `P2Tests.swift`) | App |
|---|---|---|
| Offline estimate (section 5) | `Estimate.swift`: `GreatCircle`, `estimatePosition` (same algorithm as Android `core/estimate`, tested on the same `estimate_cases.json`), `Flight.position(now:)` (Live when the fix is < 20 min old, else Estimated; fresh data that says "not departed" keeps the plane at the gate, fresh data that says "in the air" past the planned arrival keeps it airborne, same rules as Android), `Flight.progressFix(now:)`; `FlightCard.progress` / `progressSource` / `progressArrival` | `FlightProgressLine` (bar, "Lands in …", Live/Estimated chip) on the flight row and the detail screen; the list and the detail re-render from the cache every minute, so airplane mode still moves the bar |
| Map (section 5) | `FlightMap.swift`: `Flight.mapModel(now:)` (flown/remaining split under the plane, state, bounds) | `FlightMapView.swift`: SwiftUI `Map`, `.standard(emphasis: .muted)` (= `.mutedStandard`), `MKGeodesicPolyline` route in #DF754E (flown solid, rest dashed), cream airport dots with an orange ring, SF `airplane` rotated to the bearing (no map rotation, so north stays up), Live/Estimated chip. Card on the detail screen, full-screen on tap. Offline: no tiles, but route, airports and the estimated plane still draw |
| Airport Health (section 1) | `AirportHealth.swift`: parser, `typedHeadline` (the server's fixed English sentences → localised text), `healthSides(phase)`, `presentWeather`, `loadAirportHealth`; `FlightDetailScreenModel(airportHealth:)` loads on open/refresh and again after 15 min | `LiveAirportHealth` (`get_airport_health`, 5 min cache); `AirportHealthCard` (level pill + headline for all, delays/cancellations/METAR line for Pro, upsell for free) |
| Passport + Year in Review (section 2) | `Passport.swift`: parser, `monthBars`, stat tiles, `shareCard` / `yearInReviewCard`, `PassportModel` (overview / year tabs, cached per tab) | `PassportScreen.swift` (toolbar globe button on My flights): stats, by-month chart, highlights, lists, CO₂ estimate, Pro upsell when `pro_limited`; share image via `ImageRenderer` (360 × 450 pt at 3×, Paper or Night palette) + `UIActivityViewController` |
| Quiet hours + alert language (sections 3, 4) | `AlertPrefs.swift`: `AlertPrefs`, `alertPrefsPatch` (every quiet-hours change also writes `quiet_tz` = phone zone), `AlertPrefsModel` (optimistic; a refused save rolls back only itself, overlapping saves are kept) | `AlertPrefsSection` in Settings; `LiveAlertPrefs` reads/PATCHes `profiles`. First launch on a Hindi phone sets `alert_language = hi` once, only while the server still has the default `en` |
| Passive alerts (section 3) | `PushContract.keyQuiet` / `channelQuiet`; `parseIncomingPush` marks an info alert quiet on `quiet = "1"` or APNs `interruption-level: passive`; critical alerts are never quiet | Foreground: a passive alert is listed without banner or sound; alerts the app posts itself use `.passive` and no sound |
| Delivery receipts (section 3) | `PushAck.swift`: `parsePushEventId`, `PushAckRequest` (`{event_id, received_at}`), `AckedEvents` (once per event), `pushAcks` | `PushReceipts` + `LivePushAck` (`POST ack-push`, failures ignored). Acked on `willPresent`, on open (with the delivery time), when the app posts an alert itself, and on every foreground for alerts from the last 24 h still in Notification Center (stamped with `UNNotification.date`, i.e. when iOS delivered them) |
| New alert types | `AlertType.fogAlert` / `.divertedDest` (both critical), `AlertGroup.weather` (FOG_ALERT), DIVERTED_DEST in `.disruption`, tag group `diverted` | "Fog and low visibility" toggle |

## P2 wave 2 (docs/P2_WAVE2_CONTRACTS.md)

| Feature | Core (XCTest in `Wave2Tests.swift`) | App |
|---|---|---|
| Friends (section 1) | `Friends.swift`: invite codes (`normalizeInviteCode`, `extractInviteCode`, `formatInviteCode`, `inviteInput`, `heygate://friend?code=` links; `invite_code_cases.json`), parsers for `my_friends` / `friends_flights` (`flight_public_json` → `Flight`) / `accept_friend_invite`, `acceptInviteError` (P0002 / 22023 / 23505 / P0001), `FriendsModel`, `AcceptInviteModel`, `FriendsFlightsModel`, `ShareWithFriendsModel`; friend alerts in `Push.swift` (`PushAlert.isFriend` / `friendName`; only CANCELLED / DIVERTED / DIVERTED_DEST are time-sensitive for friends, as `friend-alerts.ts`) | `FriendsScreen.swift` (invite on WhatsApp / share sheet / copy code, friends' flights, friends with an alert switch and remove), `AcceptInviteScreen` (also opened by `heygate://friend?code=…`), toolbar `person.2` button and Settings row; `LiveFriends`; "Friends can see this flight" switch on the detail screen (`user_flights.share_with_friends`). A tap on a friend's flight or friend alert opens the normal detail screen read-only (`can_read_flight` lets `flight_view` return it) |
| Email forwarding (section 2) | `isForwardingAddress`, `TrackSource.email`, `IncomingPush.imported` (`kind = "import"`) | `ForwardingScreen` (Settings → Import by email): address, copy, share, Pro note; `LiveForwarding`. An import push refreshes the flight list |
| Calendar (section 3) | `CalendarLinks.swift`: `isFeedURL`, `webcalURL`, `googleCalendarSubscribeURL`, `calendarEvent(for:)` (same SUMMARY as the feed, departure + 2 h when the arrival is unknown) | "Add to calendar" on the detail screen: `EKEventEditViewController` (EventKitUI, out of process, so no calendar permission is asked); Settings → Calendar: subscribe in Apple Calendar (`webcal://`), Google Calendar, copy link, reset link (`rotate_calendar_token`). The feed URL is only created when the user taps one of them |
| Forecast (section 4) | `Forecast.swift`: `parseFlightForecast`, `forecastCardState` (times at the arrival airport, minutes against schedule), `FlightForecastModel` (asks again only when est/act departure or est arrival change; errors keep the earlier answer) | `ForecastCardView` / Pro teaser on the detail screen; `LiveForecast` |
| When to leave (section 5) | `LeaveBy.swift`: `LeaveRules`, `leaveBy`, `isPeakHour`, `leavePlan`, `AirportHealth.slowsDeparture`, directions links, `whenToLeaveState`, `reminderAction`, `replanReminder`, `WhenToLeaveModel`; tested on Android's `leave_by_cases.json` (copied unchanged) | `WhenToLeaveCardView` (leave-by time, reasons, travel-time stepper remembered per airport in UserDefaults, Apple Maps / Google Maps); `LocalLeaveReminders`: a time-sensitive local notification `<flightId>:leave` at `leaveBy − 15 min` |

iOS cannot re-check a reminder when it fires (Android's WorkManager job does). Instead `AppShell.resyncLeaveReminders()` re-plans every pending reminder from the cache after each push and whenever the app comes to the foreground, so a delay moves it and a cancellation removes it. The fog flag travels in the pending request because Airport Health is not asked from there. Without Pro the resync leaves a reminder alone; the detail screen cancels it once it knows the Pro state.

Not done here: a Notification Service Extension. It would ack the moment an alert lands even when the app is never opened, but it needs the user's JWT in a shared keychain group; the foreground sweep stamps the true delivery time instead, so only alerts the user never opens and clears before opening the app go unacknowledged.

## Languages (English, Hindi)

* Code uses `String(localized:)` (or SwiftUI string literals); English text is the key. `SWIFT_EMIT_LOC_STRINGS` lets Xcode add new keys to the catalog on build.
* `FlyTrack/Localizable.xcstrings` holds every app key with an `hi` translation; `FlightActivity/Localizable.xcstrings` holds the Live Activity's own keys (the extension is a separate bundle). Plurals use catalog plural variations, with substitutions where a string has two counts (`%lld hr %lld min`).
* Info.plist text (camera prompt, display name) is in `FlyTrack/{en,hi}.lproj/InfoPlist.strings`; `CFBundleLocalizations` lists `en`, `hi`.
* Hindi keeps brand names, flight numbers, IATA codes, times and Latin digits. A new string needs its Hindi value before release (Xcode marks it "New" in the hi column).
* The share text stays bilingual: in Hindi the Hinglish line is Devanagari and the English line with the link is kept.

## Push

One backend path. FCM HTTP v1 already delivers to Android. The same message now includes an `apns` object, which FCM applies only when the token is an iOS app. Android still reads `android.notification.channel_id` and `tag`. Nothing in the Android block changed.

| Android | iOS |
|---|---|
| channel `flight_critical` | `aps.interruption-level = time-sensitive` |
| channel `flight_updates` | `aps.interruption-level = active` |
| channel `flight_quiet` (info alert in quiet hours, data `quiet = "1"`) | `aps.interruption-level = passive`, no `sound` |
| notification tag `<flightId>:<group>` | `apns-collapse-id` and `aps.thread-id` set to that same tag |
| data-only `sync`, collapse key `sync:<id>` | background push, `apns-push-type: background`, priority 5, collapse id `sync:<id>`, `content-available: 1` |

Priority 5 is what Apple accepts for a background content-available push. A priority-10 background push is throttled. The alert itself is the visible notification.

The app uses the Firebase iOS SDK so the token stored in `devices.fcm_token` is still an FCM token. Direct APNs would have meant a second sender, a key on the Edge Function, and a different token shape. The APNs auth key is uploaded to Firebase, not to this repo.

`devices.platform` now allows `ios`. `register_device` gained `p_platform text default 'android'`. The Android client keeps calling the five-argument form. Migration: `supabase/migrations/20261122000004_ios_devices.sql`.

If `GoogleService-Info.plist` is missing, the app never calls `FirebaseApp.configure()` or `Messaging.messaging()`. Settings shows that push is not configured.

Foreground alerts the app posts itself use the tag as the notification identifier, so a newer gate alert replaces the previous one. Time-sensitive requires the entitlement `com.apple.developer.usernotifications.time-sensitive`, which is in `FlyTrack.entitlements`.

## What was not compiled here

Everything that imports UIKit, SwiftUI, SwiftData, PDFKit, Vision, UserNotifications, Firebase or Google Sign-In:

* `ios/FlyTrack/` (the whole app target, including `Backend.swift`, `Services.swift`, `Screens.swift`, `Cache.swift`, `FlyTrackApp.swift`)
* `ios/ShareExtension/ShareViewController.swift`
* `ios/project.yml` (XcodeGen input; the project is generated on the Mac)

`ios/FlyTrackCore` was compiled and tested with Swift 6.4.3 on Linux.

## Commands on the Mac

Install XcodeGen. Fastlane is only for the archive lane.

```bash
brew install xcodegen
brew install fastlane   # optional
```

Xcode 27.2 already includes Swift. No separate toolchain is required.

```bash
cd ios
cp Config/Secrets.xcconfig.example Config/Secrets.xcconfig
```

Edit `Secrets.xcconfig`: Apple Team ID, bundle id, Supabase URL, anon key, Google iOS client id, Google web client id, and the reversed iOS client id (the Google Sign-In URL scheme). Drop `GoogleService-Info.plist` into `ios/FlyTrack/`. Do not commit either file.

```bash
cd ios
xcodegen generate
```

That writes `ios/FlyTrack.xcodeproj`. XcodeGen 2.48 writes each target's `Info.plist` and entitlements from `project.yml` (`info.path` and `entitlements.path` are required). Scheme name: `FlyTrack`.

iPhone 17 is installed for both the 26.5 and 27.2 runtimes, so the `OS=26.5` suffix is what selects the older simulator.

Build and test on the iOS 26.5 simulator. iPhone 17 shipped with iOS 26, so that runtime has it. The `OS=26.5` suffix selects that runtime rather than the iOS 27.2 one.

```bash
cd ios
xcodebuild -project FlyTrack.xcodeproj -scheme FlyTrack \
  -destination 'platform=iOS Simulator,name=iPhone 17,OS=26.5' \
  build
xcodebuild -project FlyTrack.xcodeproj -scheme FlyTrack \
  -destination 'platform=iOS Simulator,name=iPhone 17,OS=26.5' \
  test
```

`test` runs `FlyTrackCoreTests` (the same cases as `swift test`) and compiles the app and the share extension.

If the simulator list uses a different device name:

```bash
xcrun simctl list devices available
xcodebuild -project FlyTrack.xcodeproj -scheme FlyTrack \
  -destination 'platform=iOS Simulator,id=<UDID from the iOS 26.5 list>' \
  test
```

Archive for TestFlight. This does not upload.

```bash
cd ios
xcodebuild -project FlyTrack.xcodeproj -scheme FlyTrack -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath "$PWD/build/HeyGate.xcarchive" \
  archive
```

Or, after `bundle install` / `brew install fastlane`:

```bash
cd ios && fastlane archive
```

Open the archive in Xcode Organizer and upload it yourself. Nothing in this repo uploads a build.

Before the first archive, on the HeyGate target in Signing & Capabilities, add **Push Notifications**. Xcode then injects `aps-environment` (development locally, production in the App Store profile). The time-sensitive entitlement and the app group `group.app.heygate.flytrack` are already in the entitlements files. Create that app group in the developer portal for both the app and the share extension.

## TestFlight checklist

1. Apple Developer Program membership, and the Team ID copied into `Secrets.xcconfig`.
2. A bundle id (the placeholder is `app.heygate.flytrack`) registered, and an App Store Connect app record with that id.
3. Automatic signing. The project uses `CODE_SIGN_STYLE = Automatic`. Replace `TEAMID` before building.
4. An APNs Auth Key (`.p8`) created in the Apple developer account and uploaded to Firebase Cloud Messaging for the same Firebase project as Android. Do not commit the key.
5. `GoogleService-Info.plist` downloaded for the iOS app in that Firebase project and added to the HeyGate target. It is gitignored.
6. Supabase Auth: anonymous sign-ins on, and manual linking on (needed for "Link Google account").
7. Google provider in Supabase: the web client id and the iOS client id, comma-separated if the dashboard asks for one field. Enable skip-nonce if the iOS ID token has no nonce. The iOS app sends the ID token from Google Sign-In with the web client as `serverClientID`, so the audience matches the Android web client.
8. Apply migrations, including `20261122000004_ios_devices.sql` (`supabase db push`), so `register_device` accepts `p_platform = 'ios'`.
9. App group `group.app.heygate.flytrack` on both targets, and the Push Notifications capability on the app.
10. Export compliance is already `ITSAppUsesNonExemptEncryption = false` (HTTPS only).

## What needs a real device

The 26.5 simulator can run the UI, the core tests, PDFKit, Vision, the document picker and the share extension. These need a physical iPhone:

* An FCM token and a delivered alert (the simulator does not get APNs).
* Time-sensitive alerts breaking through Focus.
* A TestFlight install.

Google sign-in can be tried on the simulator once the URL scheme and client ids are real. The unconfigured path (no plist, blank xcconfig) is what a fresh checkout runs, and it must not crash.
