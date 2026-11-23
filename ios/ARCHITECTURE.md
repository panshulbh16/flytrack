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

## Push

One backend path. FCM HTTP v1 already delivers to Android. The same message now includes an `apns` object, which FCM applies only when the token is an iOS app. Android still reads `android.notification.channel_id` and `tag`. Nothing in the Android block changed.

| Android | iOS |
|---|---|
| channel `flight_critical` | `aps.interruption-level = time-sensitive` |
| channel `flight_updates` | `aps.interruption-level = active` |
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
