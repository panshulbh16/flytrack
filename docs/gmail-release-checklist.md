# Gmail import: release checklist

The owner works through this list before switching `GMAIL_IMPORT_ENABLED` on in release builds. Until then, release builds hide "From Gmail", and debug builds work for test users only (see "Adding test users" in the README).

Background: `https://www.googleapis.com/auth/gmail.readonly` is a **restricted** scope. Public use needs Google's restricted-scope verification. Without it, the "unverified app" screen appears and the app is capped at 100 new users. All Gmail data, and everything derived from it, stays on the phone. The design is in `docs/superpowers/specs/2026-11-24-gmail-ticket-import-design.md`.

The Android app is shown to users as "FlyTrack" and the iOS app as "HeyGate". Below, "the app" means either one.

## 0. Device checks not yet run

These could not be run where the feature was built. Do them before anything else.

- [ ] Android `./gradlew connectedDebugAndroidTest` on a device or emulator (Room migration and store tests).
- [ ] Existing Google sign-in still works on Android after the `play-services-auth` bump to 21.6.0.
- [ ] A real Gmail pass on an Android device and on the iOS simulator, using an OAuth test user: connect, scan, add a suggestion, a background notification, Track, Not now, Disconnect Gmail, sign out.
- [ ] SQL tests: `scripts/test-sql.sh` against a local Postgres.

## 1. Google Cloud project

Use the project that already holds the Android and iOS OAuth clients (`GOOGLE_WEB_CLIENT_ID`, `GOOGLE_IOS_CLIENT_ID`).

- [ ] Enable the Gmail API: **APIs & Services → Library → Gmail API → Enable**.
- [ ] Android OAuth client for package `app.flytrack`, with the SHA-1 of the debug key, the upload key and the Play app signing key (from Play Console → Test and release → App integrity).
- [ ] iOS OAuth client for bundle id `app.heygate.flytrack` (or the `FLYTRACK_BUNDLE_ID` actually shipped).
- [ ] **Google Auth Platform → Branding**: app name, user support email, logo, app home page, privacy policy link (section 3), terms of service link. Add the home page domain under **Authorized domains**.
- [ ] The consent screen's app name must match the app name each store listing shows. Today Android is "FlyTrack" (`app_name`) and iOS is "HeyGate" (`CFBundleDisplayName`). Settle on the name (one for both, or decide how a single consent screen covers two names) before submitting for verification, and make the store listings, the privacy policy, the demo video and the consent screen agree.
- [ ] **Google Auth Platform → Data access**: add `https://www.googleapis.com/auth/gmail.readonly`. When the form asks how the scope is used, paste (replace APP with the settled name):

  > APP is a flight tracker. With the user's permission, it searches the user's Gmail for airline booking emails (subjects containing ticket, e-ticket, itinerary, booking, PNR, confirmation or boarding pass). It reads those messages and their attached PDF tickets on the user's phone to find flight numbers and dates, then suggests those flights so the user can confirm tracking them. The app needs message bodies and attachments because the flight details are in the body or the PDF ticket. gmail.metadata does not include bodies, and no narrower Gmail scope can read them. All Gmail data is processed on the user's device. None of it is sent to our servers or to any third party.

## 2. Test users (before verification)

- [ ] **Google Auth Platform → Audience**: publishing status **Testing**. Add each tester under **Test users**, up to 100.
- [ ] Testers use a debug build, which has the switch on. Grants can expire after 7 days while the app is in Testing, and the app then shows "Reconnect Gmail".
- [ ] Run the manual test on an Android device and the iOS simulator: connect (the account picker appears), scan, add, back out of Add flight (the suggestion stays), a background find with a notification, Track, Not now, disconnect, sign out.

## 3. Privacy policy (Limited Use statement)

- [ ] Publish the privacy policy on the app's home page domain, and link it from Branding and from both store listings. It must contain this section, with the affirmative statement word for word (replace APP with the settled name):

  > **Gmail import.** If you choose to connect Gmail, APP asks Google for read-only access to your email (`gmail.readonly`). APP uses this access only to find flight bookings in your email and suggest those flights for you to track. Your email is read on your phone only. No email, attachment, or information taken from them is sent to APP's servers or to anyone else. The app stores on your phone the connected Gmail address, the ids of emails it has already read, and, for each suggested flight, the flight number, date, route, booking reference and email subject. It never stores email bodies. A flight's number, date and booking reference reach APP's servers only when you confirm tracking that flight on the Add flight screen. You can disconnect at any time in Settings → Gmail, which revokes APP's access and deletes this data from your phone. You can also remove access at myaccount.google.com/permissions.
  >
  > APP's use and transfer to any other app of information received from Google APIs will adhere to the [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy), including the Limited Use requirements. In particular, use of data from Google Workspace APIs adheres to the Google API Services User Data Policy, including the Limited Use requirements. APP does not use Gmail data for advertising, does not sell it, does not use it to train generalized AI or machine learning models, and does not let any person read it unless you ask us to for specific messages, it is needed for security, or the law requires it.

- [ ] Support scripts and forms must never ask for, or record, email content. The app never logs it.

## 4. Domain verification

- [ ] Verify the home page domain, and the privacy policy domain if it is different, in [Google Search Console](https://search.google.com/search-console). Use a Google account that is an Owner or Editor of the Cloud project.
- [ ] Confirm that **Google Auth Platform → Branding** shows the domain as verified.

## 5. Demo video

Record one video in English and upload it to YouTube as **Unlisted**. Show Android first, then iOS, since both clients request the scope. Use a test account that holds a few synthetic booking emails. Do not show real personal mail.

1. Home screen: show the app name and open the app.
2. Flights → **Import a ticket**. The chooser shows **From a file or screenshot** and **From Gmail**. Tap **From Gmail**.
3. Pause about 3 seconds on the disclosure that says the app reads booking emails (read-only Gmail access) on this phone only and sends nothing from the mail to its servers.
4. Tap **Connect Gmail**. Show the account picker, then the full Google consent screen with the app name and the Gmail read permission. Pause there, then grant access.
5. The scan runs and the list shows each suggested flight with flight number, date, route and email subject.
6. Tap a suggestion. Add flight opens pre-filled. Confirm it, and the flight appears on the Flights screen.
7. Background check: with the app in the background, show the Gmail suggestion notification. Tap **Track**, and Add flight opens.
8. **Settings → Gmail**: show the connected address and the Auto-check switch. Tap **Disconnect Gmail**. The suggestions are gone.
9. On a desktop, open myaccount.google.com/permissions and show that the app's access is removed.

Add captions at steps 3 and 5: "Emails are read on this phone only. Nothing from your mail is sent to our servers."

- [ ] Video uploaded (Unlisted). Keep the link for the submission.

## 6. Verification submission

- [ ] Submit from **Google Auth Platform → Verification Center**: switch the publishing status to **In production** and submit for verification. Attach the YouTube link and the privacy policy URL.
- [ ] In the additional information field, and in replies to the review team, paste:

  > All Gmail data that the app accesses with gmail.readonly, and everything derived from it, is processed only on the user's device. The Android and iOS apps call the Gmail API directly from the phone over HTTPS with the user's OAuth token, and that token never leaves the device. No Gmail data, and no data derived from it, is stored on or transmitted to our servers or to any third party, including AI services. The only values that ever reach our server are a flight number, a date and a booking reference that the user reviews and confirms by hand on our Add flight screen. These are the same fields a user can type in manually.
  >
  > Question about the security assessment: given that no restricted-scope data is stored on or transmitted through our servers, does the app need a CASA security assessment? Please confirm in writing. If an assessment is required, please tell us which tier applies, and we will complete it before launch.

- [ ] File Google's written answer about CASA with this checklist.

## 7. CASA (if Google requires it)

- [ ] Complete the assessment with an authorized assessor named in Google's instructions before launch.
- [ ] Put annual reverification in the calendar. Google reverifies restricted-scope apps every 12 months, and the assessment repeats if it applies. Budget for it.

## 8. Store disclosures

### Google Play: Data safety

Gmail import adds nothing to the Data safety form. Under Play's rules, data accessed and processed only on the device, and never sent off it, is not "collected".

| Question | Answer for Gmail import |
|---|---|
| Messages → Emails: collected? | No. Read on the device only, never sent off it. |
| Messages → Emails: shared? | No. |
| Personal info → Email address (the connected Gmail address) | Not collected by Gmail import. It stays on the device. Keep the existing answer for Google sign-in linking. |
| Flight, date and booking reference the user confirms | Already covered by the existing answer for manually added flights. No change. |
| Data encrypted in transit | Yes (unchanged). |
| Users can request deletion | Unchanged. For Gmail, Settings → Gmail → Disconnect Gmail deletes the on-device data. |

- [ ] Play Console → App content → Data safety reviewed, and the privacy policy URL there points to the policy from section 3.

### Apple: `PrivacyInfo.xcprivacy` and App Privacy

- [ ] `ios/FlyTrack/PrivacyInfo.xcprivacy`: no change for Gmail import. Email content and the Gmail address are processed on the device and never collected (sent off the device). Gmail settings use `UserDefaults`, already declared with reason `CA92.1`. The Gmail code adds no other required-reason API.
- [ ] App Store Connect → App Privacy: no new data type for Gmail import.
- [ ] Separate from Gmail: the manifest declares no collected data types today, while the app sends tracked flights and a device token to its own backend. This predates Gmail import. Review it against the App Privacy answers before the next App Store submission.
- [ ] App Review notes, once the switch is on: say that From Gmail needs a Google account with booking emails, and give a test account that holds synthetic booking emails.

## 9. Switch it on

- [ ] Google verification approved (and CASA passed, if required).
- [ ] Android: set `GMAIL_IMPORT_ENABLED` to `true` for the `release` build type in `android/app/build.gradle.kts`.
- [ ] iOS: set the default `GMAIL_IMPORT_ENABLED` value to `YES` in `ios/Config/Shared.xcconfig` (the Debug override is already `YES`).
- [ ] Repeat the manual test from section 2 on a release build of each app with a non-test Google account.
