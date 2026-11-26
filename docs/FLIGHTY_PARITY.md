# Flighty parity plan for FlyTrack (HeyGate)

*Prepared 2026-11-26. This plan is based on public sources only. Wherever a claim could not be checked, it is marked UNVERIFIED.*

---

## 1. TL;DR

- **We already have the hard backend part.** Polling, alerts, inbound-plane warnings and the iOS Live Activity are built. The biggest missing pieces are **payments** (nothing sets `plan = pro` yet), **a map**, **Android widgets and a live notification**, **sharing**, **stats (Passport)** and **calendar**.
- **Flighty is Apple-only and charges about $59.99/yr.** About 95% of Indian users are on Android, so they cannot use Flighty at all. That is our biggest opening.
- **Many Flighty "Pro" features use US-only data** (FAA ground stops, TSA waits). In India we build substitutes from our own data instead: an "Airport Health" score, our own on-time history, and fog alerts.
- **India-first features beat Flighty:** boarding pass, PDF and screenshot import (already done); Hindi/Hinglish alerts; belt info; check-in reminders for IndiGo, Air India, Akasa and SpiceJet; WhatsApp sharing; and a much lower price.
- **Next sprint (P1):** payments with server-side entitlement, share-a-flight link, Android Glance widget and ongoing notification, check-in reminders, a 2-week coverage probe of AeroDataBox (ADB, our flight-data provider) at 6 metro airports, and the security fixes listed in HANDOFF.

---

## 2. Flighty snapshot

- **Platforms:** iPhone, iPad, Mac, Watch and Vision Pro, synced over iCloud with no account. **There is no Android app** ("not in immediate plans"). Its web presence is share-link pages, the free flighty.com/airports dashboard, and public Passport and report pages.
- **Pricing (US):**
  - Free tier.
  - Pro: $59.99/yr, $299 lifetime, $9.99/mo (likely).
  - Weekly: $4.99 or $7.99 (UNVERIFIED).
  - The annual plan can be shared with up to 6 people through Apple Family Sharing.
  - Family Lifetime at $449 is probably discontinued (UNVERIFIED).
  - India: ₹3,999–5,900/yr is quoted only on a competitor blog (UNVERIFIED).
  - Trial: your first flight gets Pro free with no card. Calendar, email and TripIt import are excluded from it.
- **What Pro unlocks:**
  - Fast, time-sensitive push and the full alert timeline.
  - Live Activities.
  - Where's My Plane, Delay Predictions (ML), Arrival Forecast.
  - Connection Assistant, Gate Predictions, When to Leave.
  - Email, calendar and TripIt import.
  - Unlimited Passport history.
  - Ground radar and proximity radar.
- **What users love:** alerts that arrive before the airline's own, the clean design, Live Activities, Friends, Passport, and Connection Assistant.
- **What users complain about:** the price, Apple-only, weak data outside the US and Asia, email import failing on non-US bookings, and accuracy bugs.

---

## 3. Parity matrix

**Status key**
- **Done:** built in our code.
- **Partial:** some pieces exist.
- **Missing:** nothing exists yet.

**Phase key**
- **P1:** next sprint.
- **P2 / P3:** later phases.
- **Skip:** we will not build it.

**★** marks a row where we can beat Flighty for India.

### Add / import

| Feature | Flighty tier | Our status | India data & feasibility | Our tier | Phase | Notes |
|---|---|---|---|---|---|---|
| Add by flight number + date | Free | **Done**. `supabase/functions/search-flight`, `_shared/engine.ts`, Android `feature/addflight/*`, iOS `AddFlightModel.swift` | ADB. Easy | Free | — | The search rate limit is racy, so a `reserve_search_quota` RPC is needed (HANDOFF #3) |
| Add by route / airport + date | Free | Missing | ADB airport departures (units cost to check). Doable | Free | P2 | Needed for "all DEL→BOM" style search |
| Past Flight Search | Unknown | Missing (the date window is −2d to +365d) | ADB historical (UNVERIFIED depth). Doable | Pro | P3 | Feeds Passport backfill |
| ★ PDF / screenshot ticket import | — (Flighty has none) | **Done**. Android `feature/importticket`, iOS `Ticket.swift` + ImportScreen; cloud fallback `parse-ticket` | On-device parsing plus an optional LLM. Indian airline and OTA templates are already covered | Free | — | Beats Flighty |
| ★ Boarding-pass barcode scan | — | **Done**. `BoardingPassScan.kt`, `ScanScreen.swift` | IATA BCBP. Easy | Free | P1 (fix) | Add a `scan` source value (BoardingPassScan.kt:39, ScanScreen.swift:90) |
| ★ Share sheet into the app | — | **Done** (Android intent filters, iOS ShareExtension) | — | Free | — | — |
| Email forwarding import | Pro | Missing | Forwarding address (trips@…) parsed in an Edge Function. No Gmail scope, so no CASA assessment needed. Doable | Pro | P2 | Flighty fails often on non-US bookings, so ★ we can do better with Indian templates |
| Gmail auto-scan | — | **Partial**. `feature/gmailimport/*`, iOS `Gmail*.swift`; behind `GMAIL_IMPORT_ENABLED` (off in release) | Restricted scope, so a CASA Tier 2 assessment is needed (about $540–720/yr, UNVERIFIED) | Pro | P3 | Ship only after verification (docs/gmail-release-checklist.md) |
| Calendar import + two-way sync | Pro | Missing (no EventKit or CalendarContract) | Device calendars are free. An .ics feed per user. Easy | Pro (feed free?) | P2 | Also helps detect crew rosters |
| TripIt sync | Pro | Missing | TripIt OAuth API; low use in India | — | Skip | Low value for India |
| Lookup by PNR alone | — | Missing (a deliberate decision) | Airlines have no public PNR API. Blocked | — | Skip | Keep the decision recorded in HANDOFF |
| Seat / class / PNR entry | Free | **Partial**. Columns `pnr`, `seat`, `cabin`; Android BookingEditorDialog; iOS display only; no cabin UI | — | Free | P1 | Add an iOS editor and a cabin picker |

### Status & alerts

| Feature | Flighty tier | Our status | India data & feasibility | Our tier | Phase | Notes |
|---|---|---|---|---|---|---|
| Unlimited basic tracking | Free | **Partial**. Free limit 10, Pro 50 (server) | ADB | Free (raise the free limit?) | P1 decision | Flighty's free tier is unlimited |
| Push alerts (delay, gate, cancel, divert) | Both (limited free) | **Done**. `_shared/diff.ts`, `engine.ts`, `fcm.ts`, `notify.ts`; 15 alert types | ADB gate and belt fill rate in India is UNVERIFIED | Free (core) / Pro (faster cadence) | — | Pro polls every 2 min, free every 3 min. A second diversion alert (DIVERTED_DEST) is still missing (HANDOFF #7) |
| Full timeline (plan filed, check-in, taxi, takeoff, belt) | Pro | **Partial**. BOARDING, DEPARTED, LANDED and BELT exist; plan-filed and taxi do not | No India flight-plan feed found; ADB OOOI events depend on coverage | Pro | P2 | — |
| ★ Check-in reminder + deep link | Pro | Missing | A static `checkin_rules` table. IndiGo's 48h open / 60–75 min close is confirmed; AI, IX, SG and QP are UNVERIFIED. Easy | **Free** | P1 | Cheap, high value. Re-verify the rules quarterly |
| Per-flight / per-group notification muting | Both | **Done**. `user_flights.notify_prefs`, AlertGroup.kt, iOS toggles | — | Free | — | Friend-level muting will come with Friends |
| Quiet hours | — | Missing | — | Free | P2 | Listed in the alert plan |
| ★ Hindi / Hinglish alerts and UI | — | Missing (only `res/values`) | — | Free | P1–P2 | Flighty has no Hindi |
| Gate / terminal / belt display | Both | **Done** (card and detail screens) | Terminal often present, gate patchy, belt patchier (UNVERIFIED). A curated airline→terminal fallback is possible | Free | P1 (probe) | ★ Belt info is useful at DEL and BOM if coverage holds |
| Pre-flight destination weather + airport delays | Pro | Missing | METAR/TAF from aviationweather.gov (free; India coverage UNVERIFIED by direct fetch) | Pro | P2 | — |
| Alert confidence labels / retraction | — | Missing (`verify.ts` exists only in the plan) | Needs a second source | Pro | P3 | Answers Flighty's accuracy complaints |
| Delivery receipts | — | Missing | — | Internal | P2 | Needed to measure "alert before the airline" |
| Test push | — | **Partial**. iOS button exists; Android has none | — | Free | P1 | notify.ts:210 notes that a foreground Android app ignores kind "test" |
| ★ SMS / WhatsApp fallback | — | Missing | TRAI DLT registration needed for SMS | Pro | P3 | — |

### Inbound plane & delay intelligence

| Feature | Flighty tier | Our status | India data & feasibility | Our tier | Phase | Notes |
|---|---|---|---|---|---|---|
| Where's My Plane (inbound tail) | Pro | **Partial**. `maybeLinkInbound` and `evaluateInbound` (fixed 35-min turnaround), INBOUND_LATE, ProInsightBlock; no map or history UI | Needs ADB tail assignment (India coverage UNVERIFIED) | Pro | P2 | Add a `turn_times` table per airline and airport, plus early prediction from the inbound leg's departure delay |
| Delay Predictions (ML + reasons) | Pro | Missing (`delay_reason` is shown only when the provider sends one) | No India ground-stop feed. Use the inbound plane, METAR fog/RVR, the ADB delay index and our own history | Pro | P3 | Start rule-based and train ML once we have enough history. Do not promise "official ground stop" |
| Arrival Forecast (route history, ~60d) | Pro | Missing | ADB `/flights/{n}/delays`, 90d (Tier 3 units) plus our own poll history | Pro | P2 | Start a per-flight history table **now**. It becomes a moat over time |
| Taxi times | Unknown | Missing | ADB OOOI events (UNVERIFIED) | Pro | P3 | — |
| ★ Airline on-time performance (DGCA) | — | Missing | DGCA monthly PDFs (free, attribute the source) | Free | P2 | Data unique to India |

### Airport & connections

| Feature | Flighty tier | Our status | India data & feasibility | Our tier | Phase | Notes |
|---|---|---|---|---|---|---|
| Airport Intelligence (status, reasons, map) | Both (gating UNVERIFIED) | Missing (airports table exists, 7,917 rows) | Derived "Airport Health" score: ADB delay index + METAR + our observed delays. Doable | Free summary / Pro detail | P2 | ★ Fog CAT-III alerts at DEL, LKO and ATQ in Dec–Jan |
| Free web airport dashboard | Free | Missing | Same data | Free | P3 | Growth channel (SEO) |
| Airport hourly delay trends | Pro | Missing | Our own aggregates plus ADB | Pro | P3 | — |
| Connection Assistant | Pro | Missing (no multi-flight trips) | No MCT or layout DB for India. Hard | Pro | P3 | Start with trips plus a simple layover-risk rule |
| Gate Predictions | Pro | Missing | Needs our own gate history; depends on gate fill rate | Pro | P3 | Only after the coverage probe |
| When to Leave | Pro | Missing | Maps ETA plus static buffers (domestic 2h, international 3h, fog and peak extras). Doable | Pro | P2 | Security wait times are blocked in India, so label times as estimates |
| Terminal maps | Pro (UNVERIFIED) | Missing | OSM indoor is sparse (UNVERIFIED); airport maps are copyrighted. Hard | — | Skip → P3 | Start with a list of key points of interest per terminal |
| ★ Crowdsourced security wait time | — | Missing | Our own users | Free | P3 | No official data source exists |

### Live tracking & map

| Feature | Flighty tier | Our status | India data & feasibility | Our tier | Phase | Notes |
|---|---|---|---|---|---|---|
| Live map, route, globe | Both | **Partial**. The `live_lat`, `live_lon` and related columns are filled by ADB but unused; Android has FlightProgressLine; MapLibre 13.7.1 is declared but never imported; iOS has no MapKit | ADB position (India density UNVERIFIED). MVP: interpolated great-circle position | Free | P2 | Do NOT use free OpenSky or ADS-B Exchange in a paid app |
| Ground radar | Pro | Missing | Needs licensed ADS-B/MLAT | — | Skip | Licensing cost |
| Proximity radar | Pro | Missing | Same | — | Skip | — |

### Widgets, live activity, watch

| Feature | Flighty tier | Our status | India data & feasibility | Our tier | Phase | Notes |
|---|---|---|---|---|---|---|
| iOS Live Activity / Dynamic Island | Pro | **Done**. `ios/FlightActivity/*`, `LiveActivity.swift`, `live_activities` table, `notify.ts` | APNs | Pro | — | Not compiled yet (Linux only). Uses system fonts only |
| ★ Android live notification (ProgressStyle) | — (no Android) | Missing | Android 16 ProgressStyle, falling back to a standard ongoing notification. Easy | Pro | P1 | Segments: check-in → boarding → taxi → airborne → landed → belt |
| Home / Lock Screen widgets | Both | Missing (Android Glance and iOS WidgetKit) | — | Free (slower refresh) / Pro | P1 (Android), P2 (iOS) | — |
| Watch app | Both | Missing | Wear OS Tile is doable | Pro | P3 | — |
| CarPlay / Android Auto | Pro | Missing | Android Auto has no eligible category, so it is blocked | — | Skip | — |
| Offline estimate | Free | **Partial**. Room and SwiftData caches exist; no estimator or connectivity observer | Bundle the airports JSON | Free | P2 | Flighty users love this |

### Sharing & social

| Feature | Flighty tier | Our status | India data & feasibility | Our tier | Phase | Notes |
|---|---|---|---|---|---|---|
| ★ Share a flight by web link | Free | Missing | Hosted page (Supabase function plus a static page) | Free | P1 | ★ WhatsApp first. A Flighty reviewer says its links only work well in iMessage. This is a growth engine |
| Friends network ("Mom has landed") | Free | Missing | — | Free | P2 | Needs the `can_read_flight()` fix first (HANDOFF #5) |
| Friends' Passports compare | Unknown | Missing | — | Free | P3 | — |

### Stats & Passport

| Feature | Flighty tier | Our status | India data & feasibility | Our tier | Phase | Notes |
|---|---|---|---|---|---|---|
| Passport (lifetime log + stats) | Both (12 months free) | Missing (only a "Past" list) | Our own data, plus carbon from Google Travel Impact Model (free, CC BY-SA) | Free 12 months / Pro unlimited | P2 | ★ Add CO₂ stats |
| Year in Review | Both | Missing | — | Free | P2 (before Dec) | Shareable, good for growth |
| Global public report | Free | Missing | Needs scale | — | P3 | PR asset |

### Aircraft & seat

| Feature | Flighty tier | Our status | India data & feasibility | Our tier | Phase | Notes |
|---|---|---|---|---|---|---|
| Aircraft type, tail, age, swap alert | Unknown | **Partial**. Model and tail shown (AircraftBlock); no age, no swap alert | ADB aircraft by registration | Free type / Pro age + swap | P2 | — |
| ★ Seat maps | None found | Missing | About 15 hand-drawn Indian configurations (our own drawings); AeroLOPA or SeatMaps licence later | Pro | P3 | Flighty appears not to have this (UNVERIFIED) |

### Settings, platform & business

| Feature | Flighty tier | Our status | India data & feasibility | Our tier | Phase | Notes |
|---|---|---|---|---|---|---|
| Cross-device sync | Both | **Done** in a different way: Supabase account (anonymous + Google) | — | Free | — | ★ Works across Android and iOS |
| ★ Android app | None | **Done** (Android app exists) | — | — | — | Main advantage |
| Crew Mode | Unknown | Missing | — | Free | P3 | Mute set for crew |
| Free Pro on first flight | Free | Missing | — | — | P1 (with payments) | Converts well |
| Family sharing | Pro | Missing | Play and App Store family plans | Pro | P3 | — |
| Gift cards | Pro | Missing | — | — | Skip | — |
| Payments / paywall | — | **Missing**. `profiles.plan`, `pro_until` and `is_pro` exist; nothing sets them | Play Billing (UPI Autopay) plus StoreKit, via RevenueCat | — | **P1** | Blocks all Pro revenue |

---

## 4. Skip or defer (and why)

| Item | Reason |
|---|---|
| Official ground stops / ground delay programs | India has no public machine-readable equivalent of FAA ATCSCC (no AAI ATFM feed found, UNVERIFIED). We build a derived "Airport Health" score instead. |
| Ground and proximity radar | Needs licensed ADS-B or MLAT data (OpenSky and ADS-B Exchange require commercial licences; quotes of about $100–500/mo are UNVERIFIED). |
| Official security wait times | No public data from DigiYatra or the airports. Use crowdsourced or heuristic estimates instead. |
| Terminal maps (full) | Airport maps are copyrighted, OSM indoor data is sparse, and Mappedin needs venue data. Defer to P3 and start with lists of points of interest. |
| TripIt sync | Low use in India. |
| Android Auto | No eligible app category. |
| PNR-only lookup | Airlines have no public PNR API. |
| Gift cards and Vision Pro | Low return for the effort. |
| Gmail OAuth in release | Needs a yearly CASA assessment. Use the forwarding address first and keep the Gmail code behind its switch. |

---

## 5. India-only advantages beyond Flighty

1. **Android first.** Flighty has nothing on Android.
2. **Ticket import from PDF, screenshot and boarding-pass barcode,** tuned for IndiGo, Air India, Air India Express, Akasa, SpiceJet, MakeMyTrip and EaseMyTrip. This is already built.
3. **Hindi and Hinglish** alerts and app text.
4. **Check-in reminders for Indian airlines, free.** Flighty charges for this.
5. **WhatsApp-first share links and family alerts.**
6. **Fog and CAT-III alerts** for DEL, LKO and ATQ in December and January, from METAR visibility and RVR.
7. **DGCA airline on-time data** at the 6 metro airports.
8. **Belt info and a curated airline→terminal map** for DEL, BOM, BLR, HYD, MAA and CCU.
9. **CO₂ per flight** from Google's Travel Impact Model.
10. **Indian pricing** with UPI Autopay and a per-trip pass.
11. **A per-flight-number history table from day one.** Over time this is a data moat.

---

## 6. Phased roadmap

### P1: next sprint (foundation + quick wins)

| # | Work item | Backend | Android | iOS | Depends on | Effort |
|---|---|---|---|---|---|---|
| 1 | **Payments and entitlements** | RevenueCat (or store webhooks) → Edge Function that sets `profiles.plan` / `pro_until`; free Pro on the first flight | Play Billing (UPI Autopay) plus a paywall screen | StoreKit plus a paywall | Pricing decision | L |
| 2 | **Security and correctness fixes** | `can_read_flight()` on flight_view (#5); `reserve_search_quota` (#3); per-user device cap and fan-out cap (#6); new Supabase key names (#4) | — | — | — | M |
| 3 | **First real run** | Real Supabase, Firebase and ADB project; run pg_cron, pg_net and Vault (#8) | Real-device test matrix (Xiaomi, Vivo, Samsung and others) | Compile the app plus its extensions on a Mac | — | M |
| 4 | **ADB coverage probe (2 weeks)** | Log the % of flights with gate, terminal, belt, tail and position at 6 metros; confirm ADB pricing and units per call | — | — | #3 | S |
| 5 | **Share a flight link** | Public tracking page plus a signed token per flight | Share intent (WhatsApp first) | ShareLink | #2 | M |
| 6 | **Check-in reminders** | `checkin_rules` table plus scheduled pushes at open and at close−30 min | Deep link to the airline | Deep link to the airline | — | S |
| 7 | **Android live notification and widget** | High-priority FCM data messages (already present) | ProgressStyle with a fallback for older Android; `flight_live` channel; Glance widget | — | — | M |
| 8 | **Small fixes** | Add a `scan` source value | Test-alert button; cabin UI | Seat, cabin and PNR editor | — | S |
| 9 | **Start flight history** | Store every poll result per flight number and date | — | — | #3 | S |

### P2: core parity (Pro worth paying for)

| # | Work item | Backend | Android | iOS | Depends on | Effort |
|---|---|---|---|---|---|---|
| 1 | Map (great-circle route plus live or interpolated position) | Expose the live fields | MapLibre (already declared) | MapKit | — | M |
| 2 | Better inbound plane | `turn_times` table; early prediction from the inbound leg's departure delay; inbound history UI | Map and history card | Same | P1 #4 | M |
| 3 | Arrival Forecast plus DGCA on-time data | ADB delays endpoint (Tier 3) plus our history; DGCA PDF ingest | Card | Card | P1 #9 | M |
| 4 | Airport Health v1 | ADB delay index plus METAR every 15–30 min; fog trigger; disruption banner | Airport screen | Airport screen | — | M |
| 5 | Email forwarding import | Inbound mail → parse-ticket pipeline; notify when parsing fails | — | — | — | M |
| 6 | Calendar | .ics feed per user | CalendarContract write | EventKit write | — | S |
| 7 | Passport plus Year in Review | Stats aggregation; Google Travel Impact Model CO₂ | Screens plus share card | Same | — | M |
| 8 | Friends | Friendship and sharing tables plus named alerts | Screens | Screens | P1 #2 | L |
| 9 | Hindi / Hinglish | Localised alert templates | `values-hi` | Localizable | — | M |
| 10 | Quiet hours, delivery receipts, DIVERTED_DEST | `ack_push`, quiet-hour filter, second diversion alert | Settings | Settings | — | M |
| 11 | Offline estimator plus bundled airports | — | ConnectivityObserver plus estimator | NWPathMonitor plus estimator | — | M |
| 12 | iOS widgets plus aircraft age and swap alert | ADB registration data | — | WidgetKit | — | S–M |
| 13 | When to Leave v1 | Static buffers (fog and peak extras) | Maps ETA intent | MapKit ETA | P2 #4 | M |

### P3: advanced and differentiation

| # | Work item | Notes | Effort |
|---|---|---|---|
| 1 | Second data source (AeroAPI) plus `verify.ts` confidence labels | Do compare-and-swap on the flight row and an atomic `apply_flight_update()` (HANDOFF #1, #2) **before** turning on ADB webhooks | L |
| 2 | Delay prediction (rules, then ML) | Needs months of our own history | L |
| 3 | Gate Predictions | Needs gate history and good fill rates | M |
| 4 | Connection Assistant v1 (trips plus layover risk) | — | L |
| 5 | Seat maps (about 15 Indian configurations, our own drawings) | — | M |
| 6 | Web airport dashboard, Wear OS tile, Crew Mode, Family plan | — | M each |
| 7 | SMS and WhatsApp fallback | Needs TRAI DLT registration | M |
| 8 | Gmail auto-scan release | After CASA approval | M |
| 9 | Crowdsourced security waits and points of interest per terminal | — | M |

---

## 7. Free vs Pro split and INR price

**Free (enough to grow word of mouth)**
- Tracking: raise the free limit from 10. Recommended: unlimited upcoming flights, as Flighty does.
- Core push alerts: delay, gate, cancel, divert, belt, at 3-minute polling.
- All import methods except email forwarding and Gmail.
- Check-in reminders.
- Share links and Friends.
- Basic map.
- Widget with slower refresh.
- Passport for the last 12 months, and Year in Review.
- Airport Health summary.
- Offline estimates.

**Pro**
- 2-minute polling.
- Inbound plane (Where's My Plane).
- Arrival Forecast and delay predictions.
- Live Activity and the Android live notification.
- Full alert timeline.
- Airport Health detail and trends.
- When to Leave.
- Email forwarding and Gmail import; calendar sync.
- Unlimited Passport.
- Aircraft age and swap alerts.
- Later: seat maps, Connection Assistant, Gate Predictions.

**Price recommendation**

| Plan | Price |
|---|---|
| **Annual** | **₹1,299/yr** |
| **Monthly** | **₹179/mo** |
| **Trip Pass** (one trip, 7 days) | **₹79** |
| **Lifetime** (optional, launch later) | **₹3,999** |

**Why this price, against the founder's ₹3,999/yr ceiling:**
- ₹3,999/yr is the top of the Indian market: App in the Air and Flighty (UNVERIFIED). Mileways is ₹3,499.
- Simpler trackers sell for ₹799–819.
- Most Indian users fly only 1–3 times a year, which is Flighty's main price complaint.
- ₹1,299 is about one-third of the ceiling and still well above the basic apps.
- A Trip Pass captures occasional flyers. Keep the ₹3,999 ceiling for Lifetime.
- All prices are under the ₹15,000 RBI mandate cap, so UPI Autopay renews without asking again.
- Store fees are about 15% on subscriptions.

---

## 8. Decisions the founder must make

| # | Decision | Recommended option |
|---|---|---|
| 1 | Annual price point | **₹1,299/yr + ₹179/mo + ₹79 Trip Pass.** Hold Lifetime ₹3,999 until P2 features ship. |
| 2 | Free tracking limit (currently 10) | **Unlimited upcoming flights free.** Gate depth (speed, inbound plane, Live Activity), not count. Keep a server cap for abuse. |
| 3 | Billing stack | **RevenueCat** for Play and App Store entitlements. Skip User Choice Billing with Razorpay for now; revisit when the 4% fee cut is worth the integration work. |
| 4 | Second data provider budget | **Wait for the 2-week ADB probe.** Add FlightAware AeroAPI only for the gaps the probe finds. |
| 5 | Email import path | **Forwarding address first** (no CASA). Keep Gmail OAuth off until revenue justifies the CASA assessment. |
| 6 | Hero India features for launch marketing | **Android live notification + WhatsApp share + free check-in reminders + Hindi alerts.** |

---

## 9. Legal and IP guardrails

- **Teardown uses public sources only:** help pages, press, store listings and reviews. No decompiling, no traffic interception, no scraping Flighty's servers.
- **Our own design and words.** No copied Flighty branding, names, UI layouts, icons, screenshots, copy or assets. Use our own feature names (for example "Inbound Plane", not "Where's My Plane"; "Airport Health", not "Airport Intelligence"). Keep following the HeyGate design tokens (docs/design-brief.md).
- **Data licensing to verify:**
  - ADB terms and pricing (UNVERIFIED).
  - OpenSky and ADS-B Exchange: commercial licence required. **Do not use their free feeds.**
  - Google Travel Impact Model: CC BY-SA, attribution needed.
  - OSM: ODbL share-alike.
  - DGCA: attribute the source.
  - aviationweather.gov: free US government data.
  - ICAO NOTAM API: paid.
- **No scraping airport flight-information displays** (DIAL, Adani and others) without legal review or a written agreement.
- **Seat maps:** draw our own. Do not copy SeatGuru or airline images.
- **India compliance:**
  - DPDP consent (already done for cloud ticket parsing; extend it to email forwarding and Friends).
  - TRAI DLT for SMS and phone OTP.
  - Play policy for foreground-service and battery-optimisation requests.
  - Google restricted-scope verification for Gmail.
- **Prediction labels:** call predictions "estimates". Never claim "official ground stop" for India.

---

## 10. Sources

**Flighty: official help pages, pricing and store listings**
- https://flighty.com/help/why-subscribe
- https://flighty.com/help/managing-my-flights
- https://flighty.com/help/flighty-notifications
- https://flighty.com/help/where-is-my-plane
- https://flighty.com/help/delay-predictions
- https://flighty.com/help/flighty-airports
- https://flighty.com/help/when-to-leave-for-airport
- https://flighty.com/help/passport
- https://flighty.com/help/email-forwarding
- https://flighty.com/help/complimentary-flighty-pro-flight
- https://flighty.com/help/calendar-import
- https://flighty.com/help/crew-mode
- https://flighty.com/help/offline-mode
- https://flighty.com/help/supported-platforms
- https://flighty.com/help/sharing-annual-family
- https://flighty.com/compare/tripit
- https://flighty.com/compare/app-in-the-air
- https://flighty.com/gift-cards
- https://flighty.com/pricing
- https://flighty.com/android-waitlist
- https://apps.apple.com/us/app/id1358823008
- https://apps.apple.com/CA/app/id1358823008
- https://apps.apple.com/gb/app/id1358823008?l=en-gb
- https://apps.apple.com/my/app/id1358823008
- https://apps.apple.com/sg/app/id1358823008
- https://apps.apple.com/us/story/id1477505677
- https://www.apppricinglab.com/iap/apple/1358823008

**Flighty: press coverage and reviews**
- https://www.macstories.net/reviews/flighty-a-pro-level-ios-app-for-frequent-travelers/
- https://www.travelcodex.com/a-test-drive-of-the-flighty-app-a-new-flight-tracking-tool/
- https://upgradedpoints.com/news/flighty-improvements-2025/
- https://www.going.com/guides/flighty-review
- https://techcrunch.com/2024/08/06/flightys-popular-flight-tracking-app-can-now-predict-delays-using-machine-learning/
- https://techcrunch.com/2023/07/25/flight-tracker-flighty-adds-a-private-social-network-for-tracking-loved-ones-travels
- https://techcrunch.com/?p=3105254
- https://9to5mac.com/2026/03/24/flighty-launches-new-airport-intelligence-feature-heres-how-it-works/
- https://9to5mac.com/2024/08/06/flighty-predict-delays-update-release/
- https://9to5mac.com/2026/07/07/flighty-update-adds-powerful-new-connection-assistant-feature/
- https://9to5mac.com/2021/09/20/flighty-2-0-is-here-with-live-tracking-radar-enhancements-globe-view-and-time-sensitive-notifications/
- https://9to5mac.com/2022/10/24/flighty-dynamic-island-iphone-live-activities/
- https://9to5mac.com/2023/12/12/flighty-year-in-review-features/
- https://apple.gadgethacks.com/news/flighty-airport-intelligence-feature-explained-what-it-can-and-cant-tell-you/
- https://macrumors.com/2026/07/07/flighty-connection-assistant/
- https://www.macrumors.com/2023/07/25/flighty-gains-flight-sharing/
- https://www.macrumors.com/2024/08/06/flighty-update-flight-delays/
- https://www.euronews.com/travel/2026/07/08/this-flight-app-is-making-airport-connections-less-stressful
- https://www.euronews.com/2026/11/12/when-should-you-leave-for-the-airport-this-app-can-tell-you-exactly-when
- https://appadvice.com/post/flighty-arrives-with-new-apple-watch-app-ios-17-standby-widget/778281
- https://www.imore.com/flight-tracker-flighty-adds-live-flight-location-sharing
- https://developer.apple.com/news/?id=970ncww4
- https://www.webull.com/news/15905735379239937
- https://thriftytraveler.com/?p=148231
- https://thriftytraveler.com/?p=141682
- https://thriftytraveler.com/reviews/flighty-pro-app
- https://worldairlinenews.com/2025/12/11/flighty-launches-its-first-year-in-review-global-passport-report-including-the-most-delayed-airlines-in-2025/
- https://www.techlicious.com/blog/delays-cost-fliers-almost-4-million-hours-in-2025-flighty-report/
- https://onemileatatime.com/guides/how-to-tell-airplane-age/
- https://johnnyjet.com/?p=173632
- https://9to5google.com/2026/07/27/aviate-androids-answer-to-flighty-is-now-available-to-download/
- https://flightelite.pages.dev/blog/flighty-price-india-vs-flightelite (competitor blog, UNVERIFIED)

**India data, platforms and payments**
- https://apis.io/apis/aerodatabox/aerodatabox-statistical-api-api/
- https://pub.dev/documentation/aerodatabox/1.0.0/openapi.api/StatisticalAPIApi/getFlightDelays.html
- https://aerodatabox.com/faq
- https://aerodatabox.com/new-year-updates/
- https://thunderbit.com/blog/best-flight-api-with-free-tiers
- https://geekflare.com/guides/flight-data-api/
- https://aviationweather.gov/data/api/
- https://camd.imd.gov.in
- https://applications.icao.int/dataservices
- https://www.newsonair.gov.in/dense-fog-disrupts-flights-across-north-india-aai-issues-advisory
- https://stockdiscovery.s3.amazonaws.com/india/company/6590/4105/DGCA-Sep25.pdf
- https://www.oneindia.com/india/dgca-data-shows-domestic-air-traffic-down-4-2-in-april-indigo-strengthens-market-dominance-8104885.html
- https://openskynetwork.github.io/opensky-api
- https://www.adsbexchange.com/?p=19798
- https://support.adsbexchange.com/hc/en-us/articles/37363886073613
- https://flightaware.com/commercial/aeroapi/
- https://aviation-edge.com/airports-data/indira-gandhi-international-airport-api-departures-arrival-schedules/
- https://aviation-edge.com/airplane-seat-configuration-api/
- https://github.com/hotessy/delhi-airport-live
- https://csmia-mumbai.adaniairports.com/Conditions-of-Use
- https://www.aerolopa.com/partners
- https://seatmaps.com/for-business
- https://jentic.com/apis/amadeus.com/amadeus-seatmap-display.md
- https://wiki.openstreetmap.org/wiki/Simple_Indoor_Tagging
- https://mappedin.com/pricing
- https://www.mappedin.com/industries/airports
- https://en.wikipedia.com/wiki/DigiYatra
- https://www.skyscanner.co.in/flights/advice/how-to-avoid-airport-queues
- https://www.goindigo.in/check-in
- https://www.goindigo.in/travel-information/en/airport-check-in-requirements.html
- https://happyfares.in/blog/web-check-in-guide-indian-airlines-2026/
- https://www.ixigo.com/airlines/web-checkin
- https://developers.google.com/travel/impact-model
- https://developers.google.com/travel/impact-model/docs/reference/rest/v1/flights/computeFlightEmissions
- https://www.icao.int/icao-carbon-emissions-calculator
- https://support.google.com/cloud/answer/13465431
- https://groups.google.com/g/google-apps-script-community/c/XgsHT2Fr7u8
- https://dev.to/rem4ik4ever/my-saas-passed-casa-tier-2-assessment-and-yours-can-to-here-is-how-1b20
- https://truto.one/google/
- https://developer.android.com/about/versions/16/features/progress-centric-notifications
- https://www.androidauthority.com/android-16-qpr1-live-updates-3573399
- https://developer.android.com/design/ui/wear/guides/surfaces/tiles-layouts
- https://developer.android.com/docs/quality-guidelines/car-app-quality
- https://blog.google/intl/en-in/products/platforms/now-pay-for-subscriptions-via-upi-on-google-play/
- https://support.google.com/googleplay/android-developer/answer/13306652
- https://inc42.com/?p=385742
- https://android-developers.googleblog.com/2026/06/play-expanded-billing.html
- https://razorpay.com/docs/payments/recurring-payments/upi/faqs
- https://developer.fastspring.com/docs/unified-payments-interface-upi-autopay
- https://apps.apple.com/in/app/id533365777
- https://apps.apple.com/in/app/id1538561618
- https://apple.co/2GlMzJo

**Verification note:** flighty.com, apps.apple.com, aerodatabox.com, aviationweather.gov, techcrunch.com and going.com could not be opened directly in this research session. Claims from them come from search snippets. Allow those hosts in network settings to confirm the details before committing spend.
