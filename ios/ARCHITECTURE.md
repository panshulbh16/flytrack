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

## P3 flight intelligence and widgets (docs/P3_CONTRACTS.md)

| Feature | Core (XCTest in `P3IntelTests.swift`, `WidgetSnapshotTests.swift`) | App |
|---|---|---|
| New alert types (section C) | `AlertType` gains `gateClosed`, `takeoff`, `atGate` (info), `aircraftSwap`, `connectionAtRisk` (Pro, critical), in EVENT_TYPES order; `alertTagGroup` follows notify.ts (GATE_CLOSED → boarding, TAKEOFF → departed, CONNECTION_AT_RISK → connection); `AlertGroup.boarding` / `.takeoffAndLanding` take the milestones, new groups `.aircraftSwap`, `.connections` (Pro) | toggles "Aircraft swap (Pro)", "Connection at risk (Pro)" |
| Block times + aircraft (A2, A5, section E) | `Flight.depOffblockAt` / `depRunwayAt` / `arrRunwayAt` / `arrOnblockAt` / `aircraftIcaoType` / `aircraftAgeYears` from `flight_view`, the cache JSON and the sync push (`ofb`, `rwd`, `rwa`, `onb`; a payload without a key keeps the cache) | `WireFlight` reads the new columns |
| Timeline (A2) | `Timeline.swift`: `parseFlightTimeline`, `timelineRows` (times in the airport of each milestone, Pro detail as typed `TimelineNote`s), `localTimeline` (offline fallback from the cached row, same state rules as the server) | `TimelineCardView` on the detail screen |
| Prediction (B1) | `Intel.swift`: `parseDelayPrediction`, `delayPredictionCard`, `Flight.wantsDelayPrediction` (72 h window); labels in the app language (`predictionLanguage`) | `DelayPredictionCardView` ("Prediction", never "forecast"); teaser for free users inside the window |
| Gate prediction (B2) | `parseGatePrediction` (`count` of `samples`), `Flight.wantsGatePrediction` (no real gate yet) | "Usually gate 42B (7 of 9)" |
| Taxi times (B3) | `parseTaxiStats`, `taxiCard` (≥ 5 samples) | "Typical taxi out at DEL: 18 min" |
| Aircraft (A5) | `aircraftCard` (type for everyone, age + swap history for Pro), `parseAircraftSwaps` (AIRCRAFT_SWAP rows of `flight_events`) | `AircraftCardView` |
| Connections (B4) | `parseConnections`, `ConnectionRisk` (contract rules), `ConnectionsModel` | per-flight cards on the detail screen, `ConnectionsScreen` (`Route.connections`) |
| Airport trends (B7) | `parseAirportTrends`, `AirportTrends.worstHour`, `AirportTrendsModel` (1–90 days) | `AirportTrendsScreen` (`Route.airportTrends(iata)`, Swift Charts by hour and by day) |
| Widgets (A4) | `WidgetSnapshot.swift`: `WidgetContract` (`group.app.flytrack`, key `widget_snapshot_v1`, kind `FlyTrackWidget`), `widgetSnapshot`, `widgetFlight` (current, else next; a landed flight stays 1 h), `widgetDisplay`, `widgetMilestones` / `widgetEntryDates` (entries at boarding, departure, arrival and roll-over), codec | `WidgetPublisher` writes the snapshot after every sync (`LiveFlightRepository.onCacheChanged`: refresh, sync push, track, untrack, archive) and reloads only when it changed; cleared on sign-out. Extension `FlyTrackWidget/` (small, medium, accessoryRectangular, accessoryInline), shares `Theme.swift` and the fonts |

`FlightIntelModel` drives the detail block: the timeline is asked once the screen knows whether the user is Pro for the flight (the offline timeline shows until then), and again only when a field it depends on or the Pro access changes, the prediction at most every 15 min (the server cache), connections every 5 min, gate / taxi / swaps once. `pro_required` (42501) turns a card into the teaser; other errors keep the earlier answer. Crew mode (B12) hides the teasers, the age hint and the links that only lead to a teaser.

The App Group `group.app.flytrack` is added next to the existing `group.app.heygate.flytrack` (share extension). Register it in the developer portal for the app and the widget (`$(FLYTRACK_BUNDLE_ID).widget`) before archiving.

## P3 search, social and Apple Watch (docs/P3_CONTRACTS.md)

| Feature | Core (XCTest in `P3SearchSocialTests.swift`) | App |
|---|---|---|
| Unlimited free tracking (A1) | `UserProfile.freeTrackingLimit` / `proTrackingLimit` = the server's anti-abuse cap (100 / 300); `TrackSource.calendar` | No count-based upsell: Settings and the paywall no longer mention a flight count; the cap message asks to remove old flights |
| Calendar import (A3, Pro) | `CalendarParser.swift`: Swift port of HeyTurbulence `calendar-parser.js` (same steps as Android's `CalendarFlightParser.kt`), tested on `Fixtures/calendar_events.json`, a byte-for-byte copy of Android's fixture; `AirportCatalog.swift` (`AirportList`, picker search); `CalendarImport.swift`: `buildCalendarSuggestions` (from today, one per flight, already-tracked marks), `CalendarImportModel` (no prompt before the tap) | `DeviceCalendar` (EventKit full access, asked only on "Scan my calendar"; `NSCalendarsFullAccessUsageDescription`), `CalendarImportScreen` (`Route.calendarImport`, from Add flight and Settings). "Add" opens Add flight with `source = calendar` (by number, or the route tab when the event has no number) |
| Route search (B5) | `RouteSearch.swift`: `RouteSearchQuery` (server rules), `RouteSearchWindow` (−2 / +30 days), `RouteSearchModel` (prefill from a calendar suggestion, swap, airline filter, results soonest first) | "By route" tab on Add flight: airport pickers over the bundled `airports.json` (a copy of Android's asset), `search-route` (2 search units), same result list |
| Past flights (B6) | `PastSearchWindow` (365 days back to tomorrow UTC), `AddFlightModel.setPastMode` / `PastFlightSearching` | "Add a past flight (for Passport)" toggle; `search-flight` with `mode: "past"`; insert with `source = manual`, `archived_at = now()` |
| Compare Passports (B10) | `SocialP3.swift`: `parseFriendPassport`, `comparePassports`, `sharedCountries`, `FriendCompareModel`; `not_shared` (42501) is one typed state | "Compare Passports" on each friend → `FriendCompareScreen` ("You vs Asha", overview / this year); Settings switch "Friends can compare Passports with me" |
| Crew mode (B12) | `ProfileFlags`, `ProfileFlagsModel` (optimistic, rolls back), `blockMinutes`, `Passport.blockMinutes` | Settings switch; cached per user (`P3Center.crewMode`). On: leave reminders cancelled, Pro upsells hidden (Settings, Passport, Airport Health, calendar import, family), block time card on the detail screen, "Block hours" in Passport |
| Family sharing (B13) | `Family.swift`: `parseFamily` (`my_family`, SQL null = no family), `familyError`, `FamilyModel` (create invite, join, leave, remove); `FamilyGrant` + `EntitlementRules.resolve(…, family:)` (`ProSource.family`: a member has no Pro of their own in `profiles`) | `FamilyScreen` (`Route.family`, `heygate://family?code=…`); `FamilyGrantCache` keeps `my_family().pro_via_family` per user and feeds `ProService`, so members get Pro screens; "Pro via {owner}'s family" in Settings |
| Apple Watch (B9) | `WatchPayload.swift`: compact flight shape (same short keys as the sync push and Android's Wear payload), `watchPayload` (the widget's choice and order), codec, App Group storage | `WatchSender` (`WCSession.updateApplicationContext`, key `next_flight`, sent from `AppShell.publishWidgets` when it changed). Targets `FlyTrackWatch` (SwiftUI next-flight screen) and `FlyTrackWatchComplication` (WidgetKit rectangular, inline, circular, corner; timeline at the flight's milestones), both on `FlyTrackCore` (now also `.watchOS("26.0")`) |

The watch app and complication share `FlyTrackWatch/WatchWording.swift` and the App Group `group.app.flytrack` (on the watch it only holds the last payload). Register `$(FLYTRACK_BUNDLE_ID).watchkitapp` and `….watchkitapp.complication` with that group before archiving. The watch is embedded in the app's `Watch` folder; the `FlyTrackWatch` scheme runs it on a paired watch simulator.

## P4 radar, TripIt, Gift Pro, terminal maps (docs/P4_CONTRACTS.md)

| Feature | Core (XCTest in `P4Tests.swift`) | App |
|---|---|---|
| Radar (section 1, Pro) | `Radar.swift`: `RadarContract` (5 / 75 km, 10 s refresh, 60 s after `rate_limited`), `roundToRadarCell` (0.05°, also rounded on the phone before sending), `RadarRequest.queryItems`, `parseRadarResponse`, `radarFailure` (body first, then status), `radarRows` (ground mode keeps aircraft on the ground, nearest first; label = matched flight, else callsign, else registration), `RadarModel` (near me waits for the tap; `nextRefreshDelay` is nil when refreshing cannot help; `feed_error` keeps the planes on screen and retries; an unavailable or unlicensed feed is "Radar is coming soon", never an error), `CoarseLocating` | `RadarScreen` (`Route.radar(RadarTarget)`): SwiftUI `Map` with the radius circle and rotated `airplane` annotations labelled by callsign / flight, list with "Track". The refresh loop runs in `.task(id:)` keyed on `scenePhase == .active`, so it stops when the screen is gone or the app is in the background. `LiveRadar` (`GET functions/v1/radar` through `P4Functions`). `CoarseLocation`: `requestWhenInUseAuthorization` + `requestLocation`, `kCLLocationAccuracyReduced`, `NSLocationDefaultAccuracyReduced = true`. Entry points: "Ground radar · DEL" under the departure / arrival blocks of the detail screen (`P4AirportLinksView`), "Planes near this flight" while airborne (no permission needed), "Planes near me" in Settings and in the flight list's overflow menu |
| TripIt (section 2, Pro) | `TripIt.swift`: `parseTripItStatus`, `parseTripItSyncSummary`, `parseTripItAuthorizeURL` (https on www.tripit.com only), `tripItFailure`, `parseTripItCallback` (`heygate://tripit/callback?code=...`, legacy `/connected`, `/failed?error=...`), `TripItModel` (connect → sign-in sheet → callback code → `tripit-complete` as the signed-in user (`not_your_link` when another account started the link) → status, then asks the status twice more for the background first sync; sync now; disconnect; `tripit_revoked` → "Connect again"; `not_configured` disables the row) | `TripItRows` in Settings → "Radar and TripIt". `WebTripItAuthorizer`: `ASWebAuthenticationSession` with `.customScheme("heygate")`, not ephemeral. `LiveTripIt`: `tripit-connect` / `tripit-complete` / `tripit-sync` (429 `cooldown`) / `tripit-disconnect` (POST) and `tripit_status()`. A `heygate://tripit/...` link opened by the system goes through `P4Center.pendingTripItCallback` to Settings |
| Gift Pro (section 3) | `Gifts.swift`: `GiftProduct` (`heygate_gift_year` 365 d, `heygate_gift_trip` 7 d, never a trip pass), `normalizeGiftCode` / `giftCodeInput` / `extractGiftCode` (invite alphabet, `XXXX-XXXX-XXXX`), `giftRedeemLink` / `giftCode(fromURL:)` (`heygate://gift?code=`), `parseMyGifts`, `parseRedeemGift` / `redeemGiftError`, `GiftStore`, `GiftPurchaseModel` (buys, then polls `my_gifts()` for a new unused code of that product; "appears in My gifts in a minute" when the webhook is late), `RedeemGiftModel` (a short code is never sent, so it never spends one of the 10 attempts), `MyGiftsModel` | Paywall tab "Gift Pro" (`PaywallScreen(initialTab:)`, `Route.giftPro`): `GiftProTab`, `GiftCodeCard` (WhatsApp, share sheet, copy; Hinglish line + English line + redeem link). `RevenueCatBilling: GiftStore` fetches the two consumables by product id (`Purchases.products`, `purchase(product:)`), so they need no offering and no entitlement. `RedeemGiftScreen` (`Route.redeemGift`, also `heygate://gift?code=`) and `MyGiftsScreen` (`Route.myGifts`) in Settings → "Gifts". A redemption refreshes the profile and the Pro state |
| Terminal maps (section 4, Pro) | `TerminalMap.swift`: `parseTerminalMap` (the `data/terminal-maps` format: kind, ref, name, level, anchor, bounds), `gateKeys` / `matchGate` (ref, else name; "Gate 042B" = "42B", "T3-42B" = "42B"; the flight's terminal decides between reused numbers), `matchBeltArea`, `matchTerminal`, `terminalPOIs` / `filterPOIs` (every word in the name, category label or level; case and diacritics ignored), `TerminalMapRequest.departure` / `.arrival`, `TerminalMapModel` | `TerminalMapScreen` (`Route.terminalMap`): `TerminalMKMapView` (MKMapView; the bundled file decoded with `MKGeoJSONDecoder` into `MKPolygon` / `MKMultiPolygon` / `MKPolyline` overlays and gate / POI markers; the user's gate, belt area and terminal in the accent colour; Apple POIs hidden), "© OpenStreetMap contributors (ODbL)" on the map, POI list with search and category chips, "Terminal map not available yet" without a file. `BundledTerminalMaps` reads `Resources/TerminalMaps/{IATA}.geojson` (folder reference in project.yml); the detail screen only offers the map when the file is in the bundle |

`P4Center` (`AppShell.p4`) owns the services. P4 Edge Function calls go through `P4Functions` (URLSession with the session's access token and the anon key) because the screens need the `{ "error": ... }` body of non-2xx answers; RPCs use `Wave2Backend`.

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
* `ios/FlyTrackWidget/` (WidgetKit extension) and the P3 app files `IntelScreens.swift`, `IntelServices.swift`
* `ios/FlyTrackWatch/`, `ios/FlyTrackWatchComplication/` and the P3 app files `P3Services.swift`, `P3Screens.swift`, `RouteSearchViews.swift`, `WatchSync.swift`
* the P4 app files `P4Services.swift`, `P4Screens.swift` and the P4 edits in `Billing.swift`, `PaywallScreen.swift`, `Screens.swift`, `FlyTrackApp.swift`
* `ios/project.yml` (XcodeGen input; the project is generated on the Mac)

`ios/FlyTrackCore` was compiled and tested with Swift 6.4.3 on Linux up to P2. The P3 and P4 additions (calendar parser, route search, social, family, watch payload, intel, widget snapshot, radar, TripIt, gifts, terminal maps and their tests) were written without a Swift toolchain: run `swift test` in `ios/FlyTrackCore` on the Mac before shipping.

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
