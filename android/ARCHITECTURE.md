# FlyTrack Android: architecture and contracts

Read this first. It lists every public contract the foundation provides, so a feature can be built
without reading the foundation's code. Signatures here are copied from the sources; when in doubt
the source file (named in each section) wins.

Contents: 1 Rules · 2 Build and test · 3 Toolchain facts · 4 Package map · 5 Data flow ·
6 Domain models · 7 Flight number, time, results · 8 Repositories · 9 Dependency injection ·
10 Theme · 11 Shared components · 12 Icons · 13 Navigation and entry points · 14 Intents and push ·
15 Shared content · 16 Strings · 17 Manifest · 18 Tests and fakes · 19 Backend not configured ·
20 Known limits

---

## 1. Rules

* Single activity, single Gradle module (`:app`), package root `app.flytrack`.
* Unidirectional data flow: a `ViewModel` exposes `StateFlow<UiState>`; composables are stateless
  and take state plus lambdas.
* **Room is the single source of truth.** Screens read `observe*` flows only. Commands go to a
  repository, which writes the network result into Room; the flows then re-emit.
* Repository commands never throw. They return typed results (`SearchResult`, `TrackResult`,
  `ActionResult`, `AuthResult`). Use an exhaustive `when`.
* Times are `kotlin.time.Instant` (UTC). Show them in the **airport's** IANA zone with
  `FlightTimeFormat`, never in the phone's zone.
* All user-visible text is in string resources (section 16).
* Keep logic that deserves tests free of `android.*` types (section 18).
* Ownership: each feature edits only its own packages and its own `strings_*.xml`. Everything in
  sections 6 to 12 belongs to the foundation.

## 2. Build and test

Always offline and under the shared lock (other agents use the same checkout and Gradle daemon):

```bash
cd /home/user/flytrack/android && export ANDROID_HOME=/opt/android-sdk && \
  flock /tmp/flytrack-gradle.lock ./gradlew --offline <tasks> 2>&1 | grep -v -E 'JAVA_|_JAVA' | tail -60
```

| Task | Use |
|---|---|
| `:app:compileDebugKotlin` | fast compile check (also runs KSP: Room + Hilt) |
| `:app:testDebugUnitTest` | JVM unit tests |
| `:app:assembleDebug` | debug APK; also validates the Hilt graph and the manifest merge |

Test reports: `app/build/reports/tests/testDebugUnitTest/index.html`, XML in
`app/build/test-results/testDebugUnitTest/`.

Do not add, remove or bump dependencies or plugins. Everything in `gradle/libs.versions.toml` is
in the offline Gradle cache; nothing else is.

Backend keys are read from `android/local.properties` (never committed) or environment variables:
`SUPABASE_URL`, `SUPABASE_ANON_KEY`, `GOOGLE_WEB_CLIENT_ID`. They become `BuildConfig` fields. All
three are blank in this checkout (section 19).

## 3. Toolchain facts (newer than most documentation)

Gradle 9.9.2, AGP 9.5.2 (built-in Kotlin), Kotlin 2.4.21, KSP 2.3.14, Compose BOM 2026.11.01,
Material 3 1.5, Navigation 2.10.2, Room 2.8.5, Hilt 2.60.1, supabase-kt 3.9.0, Ktor 3.6.1,
kotlinx-datetime 0.8.0, compileSdk 37.2, targetSdk 36, minSdk 26.

Things that were verified against the real artifacts while building the foundation:

* **`Instant` and `Clock` are `kotlin.time.Instant` / `kotlin.time.Clock`** (stdlib, stable, no
  opt-in). `kotlinx.datetime` only supplies `LocalDate` here. Do not import
  `kotlinx.datetime.Instant`.
* **There is no Material icons artifact** in the dependency set: `Icons.Default.*` does not exist.
  Use `painterResource(R.drawable.ic_…)` (section 12).
* `hiltViewModel()` is `androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel`.
* `collectAsStateWithLifecycle()` is in `androidx.lifecycle.compose`.
* Type-safe navigation: `composable<Route> { entry -> entry.toRoute<Route>() }`.
* Use `FlyTrackTopBar` instead of Material's `TopAppBar`: it carries the brand colours and the
  experimental-API opt-in in one place.
* The Google Fonts provider certificates ship in `res/values/font_certs.xml`
  (`R.array.com_google_android_gms_fonts_certs`).
* supabase-kt runs on the plain JVM: Postgrest, Functions and even Auth work in unit tests with
  `ktor-client-mock` (see `SupabaseBackendApiTest`, `SupabaseAuthGatewayTest`). Real DataStore
  works on the JVM with a temp file (`DataStoreSettingsStoreTest`). **Room does not**: there is no
  JVM SQLite driver in the dependency set, so DAOs are faked (`InMemoryDatabase`).
* There is no Robolectric, emulator or device. `android.*` calls in a unit test throw
  "Method … not mocked".
* When an API does not compile, inspect the artifact under
  `~/.gradle/caches/modules-2/files-2.1` (read-only) with `unzip -l` and `javap -cp`.

## 4. Package map

```
app.flytrack
├─ FlyTrackApp            Application: Hilt, WorkManager config (HiltWorkerFactory), PushInitializer.onAppStart()
├─ MainActivity           single activity: theme, shell, Intent -> IncomingIntent
├─ MainViewModel          launch bootstrap (sign in, first sync) and intent routing
├─ core                   pure Kotlin, no android.* (JVM-tested)
│  ├─ model               Flight, TrackedFlight, Airport, UserProfile, enums, derived helpers
│  ├─ flightnumber        parseFlightNumber (port of the server's parser)
│  ├─ time                FlightTimeFormat, RelativeDay/RelativeTime, DurationParts, SearchWindow
│  ├─ result              ActionResult, SearchResult, TrackResult, AuthResult
│  ├─ push                PushContract (channel ids, click action, FCM data keys)
│  └─ share               SharedContent, SharedContentHolder
├─ data
│  ├─ local               Room: entities, DAOs, Converters, FlyTrackDatabase, TransactionRunner
│  ├─ remote              BackendConfig, SupabaseClientProvider, BackendApi (+Supabase impl),
│  │                      AuthGateway (+Supabase impl), BackendException, FlyTrackJson, Mappers, dto/*
│  └─ repo                AuthRepository, FlightRepository, DeviceRepository, AirportRepository,
│                         ProfileRepository, SettingsStore (interfaces + implementations)
├─ di                     Hilt modules and qualifiers
├─ ui
│  ├─ theme               FlyTrackTheme, colours, FlightStatusColors, typography, shapes
│  ├─ components          shared composables
│  └─ nav                 routes, FlyTrackNavHost, AppNavigator, LaunchRequest, openFlightIntent
├─ feature
│  ├─ flights             FlightsRoute, FlightDetailRoute
│  ├─ addflight           AddFlightRoute
│  ├─ settings            SettingsRoute (account, default alerts, notification health)
│  └─ importticket        ImportTicketRoute (on-device parser, optional parse-ticket)
└─ push                   FlyTrackMessagingService, FirebasePushInitializer, channels, sync worker
```

Tests mirror the packages under `app/src/test/kotlin`. Shared test support is in
`app.flytrack.testing`. The Room schema is exported to `android/app/schemas/` (commit it).

## 5. Data flow

```
Composable ──events──► ViewModel ──command──► Repository ──► BackendApi (Supabase: PostgREST, Edge Functions)
    ▲                     ▲                        │
    └──── UiState ────────┘◄── Flow ◄── Room ◄─────┘  (network results are written to Room)
FCM sync message ──► FlightRepository.applyServerSync() ──► Room ──► the same flows
```

* The app shell (`MainViewModel`) calls `AuthRepository.ensureSignedIn()` at launch (anonymous
  sign-in on first start) and then `FlightRepository.refresh()` and `ProfileRepository.refresh()`
  once. Screens call `refresh()` themselves for pull-to-refresh or when they come back to the
  foreground.
* Every repository command calls `ensureSignedIn()` first, so a failed sign-in at launch (offline)
  heals on the next command.
* Flights are always addressed by `Flight.id` (= `flights.id`). Push messages (`flight_id`) and
  the `FlightDetail` route use the same id.

### Airports (decision)

The 7,917-row seed is **not** bundled in this step. Airports reach Room in two ways:

1. every `flight_view` row embeds its two airports (`dep_name`, `dep_city`, `dep_lat`, `dep_lon`,
   `dep_tz`, and the `arr_*` twins). They are cached as a side effect of search, track and
   refresh, so a tracked flight never needs an extra request;
2. on demand through `AirportRepository.getAirport(iata)`: Room first, else
   `public.airports` via PostgREST, then cached.

`Flight.depAirport` / `Flight.arrAirport` are therefore already filled for anything that came from
the backend. They are null only when the backend's airports table does not know the airport.

## 6. Domain models (`app.flytrack.core.model`)

```kotlin
enum class FlightStatus(val wire: String) {            // mirrors FLIGHT_STATUSES (types.ts)
    UNKNOWN, SCHEDULED, CHECK_IN, BOARDING, GATE_CLOSED, DEPARTED, EN_ROUTE, APPROACHING,
    LANDED, ARRIVED, CANCELLED, DIVERTED;
    companion object { fun fromWire(value: String?): FlightStatus }   // unknown value -> UNKNOWN
}
enum class FlightPhase { UPCOMING, CHECK_IN, BOARDING, IN_AIR, LANDED, CANCELLED, DIVERTED }
enum class FlightStatusTone { ON_TIME, DELAYED, CANCELLED, ACTIVE, ESTIMATED }   // -> theme colours

enum class AlertType(val critical: Boolean, val proOnly: Boolean = false) {   // mirrors EVENT_TYPES
    DELAY, DELAY_REDUCED, SCHEDULE_CHANGE, GATE_ASSIGNED, GATE_CHANGE, TERMINAL_CHANGE, BOARDING,
    DEPARTED, LANDED, BELT_ASSIGNED, BELT_CHANGE, CANCELLED, DIVERTED, REINSTATED, INBOUND_LATE;
    val wire: String                                         // = name; FCM `event_type`, notify_prefs key
    companion object { fun fromWire(value: String?): AlertType? }
}
// critical = true  -> channel flight_critical: GATE_CHANGE, TERMINAL_CHANGE, BOARDING, BELT_ASSIGNED,
//                     BELT_CHANGE, DELAY, SCHEDULE_CHANGE, CANCELLED, DIVERTED, REINSTATED, INBOUND_LATE
// critical = false -> channel flight_updates: DELAY_REDUCED, GATE_ASSIGNED, DEPARTED, LANDED
// proOnly = true only for INBOUND_LATE

data class Airport(val iata: String, val name: String, val city: String?, val tz: String,
                   val lat: Double, val lon: Double) { val displayCity: String }

data class LivePosition(val lat: Double, val lon: Double, val altFt: Int?, val headingDeg: Double?,
                        val speedKts: Double?, val at: Instant?)
data class InboundAircraft(val flight: String, val fromIata: String, val status: FlightStatus,
                           val schedArr: Instant?, val estArr: Instant?, val actArr: Instant?,
                           val live: LivePosition?)
data class InboundPrediction(val computedAt: Instant?, val predictedDep: Instant?, val lateMin: Int,
                             val reason: String?, val inbound: InboundAircraft?)

data class Flight(                                       // one row of public.flight_view
    val id: String, val carrierIata: String, val flightNumber: String, val flightDate: LocalDate,
    val depIata: String, val arrIata: String, val airlineName: String?, val status: FlightStatus,
    val schedDep: Instant?, val estDep: Instant?, val actDep: Instant?,
    val depTerminal: String?, val depGate: String?, val depCheckin: String?,
    val schedArr: Instant?, val estArr: Instant?, val actArr: Instant?,
    val arrTerminal: String?, val arrGate: String?, val baggageBelt: String?,
    val aircraftModel: String?, val aircraftReg: String?, val delayReason: String?,
    val live: LivePosition?,
    val lastPolledAt: Instant?, val updatedAt: Instant?,
    val depAirport: Airport?, val arrAirport: Airport?,
    val inboundFlightId: String?,          // Pro only, null for free users
    val prediction: InboundPrediction?,    // Pro only, null for free users
    val hasProInsight: Boolean,            // true for everyone when a 10+ min Pro prediction exists (paywall teaser)
) {
    val designator: String                 // "6E 2341"
    val depTz: String?                     // depAirport?.tz
    val arrTz: String?
}

enum class Cabin(val wire: String) { ECONOMY, PREMIUM_ECONOMY, BUSINESS, FIRST }
enum class TrackSource(val wire: String) { MANUAL, PDF, SHARE, SCREENSHOT }

data class TrackedFlight(                                // one row of public.user_flights
    val id: String, val userId: String, val flightId: String,
    val pnr: String?, val seat: String?, val cabin: Cabin?, val source: TrackSource,
    val notifyPrefs: Map<AlertType, Boolean>, val note: String?,
    val archivedAt: Instant?, val createdAt: Instant?,
) {
    val isArchived: Boolean
    fun notifies(type: AlertType): Boolean // false only when the pref is exactly false (server rule)
}
data class TrackedFlightDetails(val tracking: TrackedFlight, val flight: Flight)

data class TrackExtras(val pnr: String? = null, val seat: String? = null, val cabin: Cabin? = null,
                       val source: TrackSource = TrackSource.MANUAL, val note: String? = null) {
    companion object { const val MAX_SEAT_LENGTH = 5; const val MAX_NOTE_LENGTH = 200 }
}
fun normalizePnr(input: String?): String?   // upper-case, strips spaces/dashes, null unless ^[A-Z0-9]{5,8}$

enum class Plan(val wire: String) { FREE, PRO }
data class UserProfile(val id: String, val displayName: String?, val plan: Plan, val proUntil: Instant?) {
    fun isPro(now: Instant): Boolean        // same rule as SQL is_pro()
    fun trackingLimit(now: Instant): Int    // 10 free, 50 Pro
    companion object { const val FREE_TRACKING_LIMIT = 10; const val PRO_TRACKING_LIMIT = 50 }
}
```

Derived helpers (`FlightDerived.kt`, all pure, unit-tested):

```kotlin
enum class TimeKind { SCHEDULED, ESTIMATED, ACTUAL }
data class BestTime(val instant: Instant, val kind: TimeKind)

val Flight.bestDepartureTime: BestTime?   // actual, else estimated, else scheduled
val Flight.bestArrivalTime: BestTime?
val Flight.bestDeparture: Instant?
val Flight.bestArrival: Instant?
val Flight.departureDelayMinutes: Int?    // (actual ?: estimated) - scheduled, rounded like the server; negative = early; null = unknown
val Flight.arrivalDelayMinutes: Int?
val Flight.duration: Duration?            // best departure to best arrival
val Flight.hasDeparted: Boolean           // server's isDeparted
val Flight.hasLanded: Boolean             // server's isLanded
val Flight.phase: FlightPhase
val Flight.statusTone: FlightStatusTone   // CANCELLED > DELAYED (>= 15 min) > ACTIVE > ESTIMATED (status unknown) > ON_TIME
fun Flight.progress(now: Instant): Float? // 0..1 flown, null before take-off
val Flight.distanceKm: Int?               // great circle between the airports
const val DELAY_THRESHOLD_MINUTES = 15
```

`ServerContractTest` reads `supabase/functions/_shared/types.ts` and `notify.ts` and fails when
these enums or the push constants drift from the backend.

## 7. Flight number, time, results

### `app.flytrack.core.flightnumber`

```kotlin
data class FlightNumber(val carrier: String, val number: String) { val display: String }  // "6E 2341"
fun parseFlightNumber(input: String): FlightNumber?
```

Exact port of the server's `parseFlightNumber`: accepts `"6E 2341"`, `"6e2341"`, `"AI-101"`,
`" ai - 101 "`; strips leading zeros (`"6E 0234"` → `234`); keeps one suffix letter upper-cased
(`"ai101a"` → `101A`); rejects two-digit carriers (`"12 345"`), more than four digits, and anything
else. Use it for live validation in the add-flight form and on OCR output.

### `app.flytrack.core.time`

```kotlin
const val FALLBACK_TIME_ZONE = "Asia/Kolkata"      // used when tz is null/unknown, like the server
fun zoneOf(tz: String?): java.time.ZoneId

object FlightTimeFormat {
    fun time(instant: Instant, tz: String?): String                               // "18:50" (24 h, ASCII digits)
    fun dayMonth(instant: Instant, tz: String?, locale: Locale = default): String // "25 Nov"
    fun dayMonth(date: LocalDate, locale: Locale = default): String
    fun weekdayDayMonth(instant: Instant, tz: String?, locale: Locale = default): String   // "Wed, 25 Nov"
    fun weekdayDayMonth(date: LocalDate, locale: Locale = default): String
    fun fullDate(date: LocalDate, locale: Locale = default): String               // "Wed, 25 Nov 2026"
    fun localDate(instant: Instant, tz: String?): LocalDate                       // calendar date at the airport
    fun arrivalDayOffset(dep: Instant, depTz: String?, arr: Instant, arrTz: String?): Int   // 1 = "+1"
}

sealed interface RelativeDay { Today; Tomorrow; Yesterday; InDays(days); DaysAgo(days) }
fun relativeDay(target: LocalDate, today: LocalDate): RelativeDay
fun relativeDay(target: Instant, now: Instant, tz: String?): RelativeDay    // calendar days at the airport

enum class RelativeUnit { NOW, MINUTES, HOURS, DAYS }
data class RelativeTime(val amount: Int, val unit: RelativeUnit, val isPast: Boolean)
fun relativeTime(target: Instant, now: Instant): RelativeTime               // whole units, rounded down

data class DurationParts(val hours: Int, val minutes: Int) { fun format(hoursMinutesPattern, minutesPattern): String }
fun Duration.toDurationParts(): DurationParts                               // rounded to the minute

fun Clock.today(tz: String?): LocalDate

object SearchWindow {                    // the dates the backend's search accepts (2 days back .. 365 days ahead)
    fun contains(date: LocalDate, now: Instant): Boolean
    fun dates(now: Instant): ClosedRange<LocalDate>     // for date pickers
}
```

The typed values become localised text through the composables in section 11
(`relativeDayText`, `relativeTimeText`, `updatedAgoText`, `durationText`).

**Clock:** inject `kotlin.time.Clock` (Hilt provides `Clock.System`). Never call `Clock.System`
in a ViewModel. Tests use `FakeClock`.

### `app.flytrack.core.result`

```kotlin
sealed interface ActionResult {          // commands and syncs without data
    Success; Offline; NotConfigured; NotFound; TrackingLimitReached
    InvalidInput(field: TrackField)      // updateTrackingDetails only
    Failed(message: String?)             // message is technical English: log it, do not show it
    val isSuccess: Boolean
}
sealed interface SearchResult {
    Found(legs: List<Flight>)            // one per leg, sorted by departure
    NotFound; RateLimited; BadInput(reason: BadInputReason); ProviderError; Offline; NotConfigured
}
enum class BadInputReason { FLIGHT_NUMBER, DATE, OTHER }
sealed interface TrackResult {
    Tracked(tracking: TrackedFlight); AlreadyTracked(tracking: TrackedFlight)
    TrackingLimitReached(limitHint: String?)     // free 10 / Pro 50 active flights
    InvalidInput(field: TrackField); FlightNotFound; Offline; NotConfigured; Failed(message: String?)
}
enum class TrackField { PNR, SEAT, NOTE }
sealed interface AuthResult { Success; Offline; NotConfigured; GoogleAccountInUse; Failed(message: String?); val isSuccess }
```

## 8. Repositories (`app.flytrack.data.repo`)

All are Hilt singletons; inject the interface.

```kotlin
sealed interface SessionState { Loading; NotConfigured; SignedOut; SignedIn(userId, isAnonymous, email) }

interface AuthRepository {
    val session: StateFlow<SessionState>
    val currentUserId: String?
    suspend fun ensureSignedIn(): AuthResult                         // anonymous sign-in on first launch; renews an expired token
    suspend fun linkGoogle(idToken: String, nonce: String? = null): AuthResult        // keeps the user id; GoogleAccountInUse possible
    suspend fun signInWithGoogle(idToken: String, nonce: String? = null): AuthResult  // switches user, wipes local user data
    suspend fun signOut(): AuthResult                                // wipes local data, starts a fresh anonymous session
}

interface FlightRepository {
    fun observeTrackedFlights(): Flow<List<TrackedFlightDetails>>    // non-archived, soonest departure first
    fun observeTrackedFlight(flightId: String): Flow<TrackedFlightDetails?>   // archived included; null = not tracked/not cached
    fun observeFlight(flightId: String): Flow<Flight?>               // any cached flight (search result, inbound leg)
    suspend fun search(flightText: String, date: LocalDate): SearchResult
    suspend fun track(flightId: String, extras: TrackExtras = TrackExtras()): TrackResult
    suspend fun untrack(flightId: String): ActionResult              // deletes the user_flights row
    suspend fun archive(flightId: String, archived: Boolean = true): ActionResult
    suspend fun updateNotifyPrefs(flightId: String, prefs: Map<AlertType, Boolean>): ActionResult   // merges; false = muted
    suspend fun updateTrackingDetails(flightId: String, extras: TrackExtras): ActionResult          // pnr/seat/cabin/note
    suspend fun refresh(): ActionResult                              // user_flights + flight_view -> Room
    suspend fun applyServerSync(flightId: String, compactFlightJson: String? = null): ActionResult  // push-triggered
}

interface DeviceRepository {
    suspend fun registerDevice(fcmToken: String, appVersion: String? = null, osVersion: String? = null,
                               locale: String? = null, timezone: String? = null): ActionResult
    suspend fun unregisterDevice(fcmToken: String): ActionResult
    suspend fun setNotificationsEnabled(fcmToken: String, enabled: Boolean): ActionResult   // devices.notifications_enabled
}

interface AirportRepository {
    suspend fun getAirport(iata: String): Airport?                   // Room, else public.airports (cached)
    suspend fun ensureCached(iatas: Collection<String>): ActionResult
}

interface ProfileRepository {
    fun observeProfile(): Flow<UserProfile?>                          // plan / Pro state; null until refreshed
    suspend fun refresh(): ActionResult
}

interface SettingsStore {                                             // DataStore, device-local
    val cloudTicketParsingConsent: Flow<Boolean>;      suspend fun setCloudTicketParsingConsent(granted: Boolean)   // default false
    val notificationPermissionAsked: Flow<Boolean>;    suspend fun setNotificationPermissionAsked(asked: Boolean)
    val lastDeviceRegistration: Flow<DeviceRegistration?>;  suspend fun setLastDeviceRegistration(registration: DeviceRegistration?)
}
data class DeviceRegistration(val fcmToken: String, val userId: String)
```

Behaviour worth knowing:

* **`search`** validates locally first (`parseFlightNumber`, `SearchWindow`) and returns
  `BadInput` without a network call. It sends the normalised number (`"6E 2341"`) and the ISO
  date to the `search-flight` function. Mapping: `not_found` → `NotFound`; `rate_limited` or HTTP
  429 → `RateLimited`; `bad_input` → `BadInput`; `provider_error`, 5xx, an undeployed function or
  an unreadable answer → `ProviderError`. Found legs are cached, so `observeFlight(id)` works
  before the flight is tracked.
* **`track`** normalises and validates the extras (PNR upper-cased, `^[A-Z0-9]{5,8}$`; seat up to 5
  characters; note up to 200; blank = not given). The database's tracking limit becomes
  `TrackingLimitReached`. A flight already tracked returns `AlreadyTracked`; one that was archived
  is un-archived and returns `Tracked`.
* **`archive(false)`** can return `TrackingLimitReached` (the limit counts active flights).
* **`updateNotifyPrefs`** changes only the given entries and keeps alert types this app version
  does not know.
* **`applyServerSync(flightId, compactFlightJson)`**: pass the `flight` value of an FCM sync
  message. It is merged into Room without any network (a payload older than the cached row is
  ignored), then the full `flight_view` row is fetched. It returns `Success` when either step
  updated Room, so it is safe to call from `onMessageReceived` with a flaky connection.
* **`registerDevice`** calls the RPC with exactly `p_token, p_app_version, p_os_version, p_locale,
  p_timezone`, then stores `DeviceRegistration(token, userId)` in `SettingsStore`. It is an
  upsert: call it on every app start and from `onNewToken`. Compare
  `SettingsStore.lastDeviceRegistration` with the current token and `AuthRepository.currentUserId`
  to skip redundant calls.
* **`signOut`**: unregister the device token first (`DeviceRepository.unregisterDevice`), then
  sign out, then register again for the new anonymous user.
* **Offline start.** When the access token expired while the app was closed and there is no
  connection, the session stays `SignedIn` (the cached flights remain visible) and commands
  return `Offline`. A new anonymous user is created only when there is certainly no stored session.

### Lower layer (normally not needed by features)

`app.flytrack.data.remote.BackendApi` is the one interface in front of Supabase. Besides the calls
the repositories use, it has a generic door for Edge Functions without a dedicated method:

```kotlin
suspend fun invokeFunction(name: String, jsonBody: String): String   // POST /functions/v1/<name>, returns the body text
```

It throws `BackendException`: `NotConfigured`, `Network(cause)`, `Http(status, code, message, hint)`
(for Edge Functions `code`/`message` are the `{ "error", "message" }` fields of the body),
`Malformed(cause)`. The importer builds its `parse-ticket` client on this: inject `BackendApi`
and `AuthRepository`, call `ensureSignedIn()`, then `invokeFunction("parse-ticket", json)`, and
keep the request/response DTOs in `feature.importticket`. `FakeBackendApi.functionHandlers` fakes
it in tests. Use `FlyTrackJson` (also injectable as `Json`) for encoding and decoding.

`SupabaseClientProvider.client` (nullable `SupabaseClient`) is injectable for anything supabase-kt
offers that the interface does not; prefer the interface.

## 9. Dependency injection (`app.flytrack.di`)

Injectable everywhere (all in `SingletonComponent`):

| Type | Notes |
|---|---|
| `kotlin.time.Clock` | `Clock.System` |
| `kotlinx.serialization.json.Json` | `FlyTrackJson`: ignores unknown keys, omits nulls |
| `@IoDispatcher CoroutineDispatcher` | `Dispatchers.IO` |
| `@DefaultDispatcher CoroutineDispatcher` | `Dispatchers.Default` |
| `@ApplicationScope CoroutineScope` | lives as long as the process |
| `BackendConfig` | `isConfigured`, `isGoogleSignInConfigured`, `googleWebClientId` |
| the six repositories/stores of section 8 | |
| `SharedContentHolder` | section 15 |
| `BackendApi`, `SupabaseClientProvider`, `AuthGateway` | lower layer |
| `FlyTrackDatabase` and its DAOs, `TransactionRunner`, `DataStore<Preferences>` | lower layer |

Constructor-inject dispatchers into anything that switches threads, so tests can pass a test
dispatcher. A feature adds its own bindings with its own `@Module @InstallIn(SingletonComponent::class)`
inside its package (the push package already has `PushModule`).

`@HiltWorker` workers work out of the box: `FlyTrackApp` is the `Configuration.Provider` and the
manifest removes WorkManager's default initializer.

## 10. Theme (`app.flytrack.ui.theme`)

```kotlin
@Composable fun FlyTrackTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)

object FlyTrackTheme {
    val statusColors: FlightStatusColors   // @Composable getter
    val type: FlyTrackExtraType            // @Composable getter
    val accentText: Color                  // terracotta that is legible as small text in both themes
}

data class StatusColor(val content: Color, val container: Color, val onContainer: Color, val outline: Color? = null)
data class FlightStatusColors(val onTime, val delayed, val cancelled, val active, val estimated) {
    fun forTone(tone: FlightStatusTone): StatusColor
}
data class FlyTrackExtraType(
    val numeral: TextStyle,          // DM Sans 400, 38sp, -1 tracking, tabular numerals: departure/arrival times
    val numeralSmall: TextStyle,     // 22sp
    val statusWord: TextStyle,       // Instrument Serif italic 28sp
    val statusWordSmall: TextStyle,  // italic 20sp
    val caption: TextStyle,          // 11sp, +1.2 tracking (upper-case the text)
)
val InstrumentSerifFamily: FontFamily
val DmSansFamily: FontFamily
```

* Material roles (`MaterialTheme.colorScheme`) carry every colour of `docs/design-brief.md`, light
  and dark. Roles the brief leaves open are derived from the same hues and marked `// derived` in
  `Color.kt`. No dynamic colour.
* `MaterialTheme.typography`: display and headline = Instrument Serif; title, body, label = DM
  Sans with tabular numerals. Fonts are downloadable Google Fonts; text falls back to a system
  font until they arrive.
* `MaterialTheme.shapes`: extraSmall 4dp, small 6dp (buttons, inputs), medium 10dp (cards),
  large 14dp, extraLarge 16dp (sheet top). Chips use `CircleShape`.
* `StatusColor.content` is for a status word on the paper background; `container` +
  `onContainer` are for pills. `onContainer` is darker than the brief's raw pair in the light
  theme, because the raw pairs miss WCAG AA for small text.
* Colour never carries meaning alone: always pair a tone with a status text.

## 11. Shared components (`app.flytrack.ui.components`)

```kotlin
@Composable fun HairlineDivider(modifier: Modifier = Modifier, color: Color = outlineVariant)
@Composable fun SectionCaption(text: String, modifier: Modifier = Modifier, color: Color = FlyTrackTheme.accentText)  // uppercase, heading semantics
@Composable fun StatusPill(text: String, tone: FlightStatusTone, modifier: Modifier = Modifier, icon: Painter? = null)
@Composable fun StatusWord(text: String, tone: FlightStatusTone, modifier: Modifier = Modifier, style: TextStyle = FlyTrackTheme.type.statusWord)
@Composable fun NumeralTimeText(time: String, modifier: Modifier = Modifier, style: TextStyle = FlyTrackTheme.type.numeral,
                                color: Color = LocalContentColor.current, struckThrough: Boolean = false, contentDescription: String? = null)
@Composable fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                              loading: Boolean = false, icon: Painter? = null)
@Composable fun SecondaryButton(/* same parameters */)
@Composable fun IconAction(icon: Painter, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true)  // 48dp target
@Composable fun FlyTrackTopBar(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {})

@Composable fun EmptyState(title: String, modifier: Modifier = Modifier, message: String? = null, icon: Painter? = ic_plane,
                           actionLabel: String? = null, onAction: (() -> Unit)? = null)
@Composable fun ErrorState(title: String, modifier: Modifier = Modifier, message: String? = null, icon: Painter? = ic_warning,
                           retryLabel: String = "Try again", onRetry: (() -> Unit)? = null)
@Composable fun OfflineState(modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null)
@Composable fun LoadingState(modifier: Modifier = Modifier, label: String? = "Loading…")
@Composable fun BackendNotConfiguredState(modifier: Modifier = Modifier)
@Composable fun BackendNotConfiguredBanner(modifier: Modifier = Modifier)     // used by the app shell

@Composable fun relativeDayText(day: RelativeDay): String        // "Today", "in 3 days", "2 days ago"
@Composable fun relativeTimeText(time: RelativeTime): String     // "now", "in 35 min", "4 min ago"
@Composable fun updatedAgoText(updatedAt: Instant, now: Instant): String   // "Updated 4 min ago"
@Composable fun durationText(duration: Duration): String         // "2h 05m"
```

Screens bring their own `Scaffold`; the app is edge-to-edge, so let `Scaffold` handle the insets.

## 12. Icons (`res/drawable/ic_*.xml`)

Line icons on a 24dp grid, 1.6 stroke, round caps. Black strokes: tint them through
`Icon(painter, contentDescription, tint = …)`.

`ic_add`, `ic_archive`, `ic_arrow_back`, `ic_baggage`, `ic_bell`, `ic_bell_off`, `ic_calendar`,
`ic_check`, `ic_chevron_right`, `ic_clock`, `ic_close`, `ic_cloud_off`, `ic_delete`,
`ic_document`, `ic_edit`, `ic_expand_more`, `ic_gate`, `ic_image`, `ic_info`, `ic_landing`,
`ic_lock`, `ic_more_vert`, `ic_person`, `ic_plane`, `ic_refresh`, `ic_search`, `ic_settings`,
`ic_share`, `ic_takeoff`, `ic_ticket`, `ic_warning`.

`ic_notification` is the white, filled status-bar icon for notifications (also the FCM default
icon). A feature that needs another icon adds it with its own prefix, for example
`ic_import_camera.xml`.

## 13. Navigation and entry points (`app.flytrack.ui.nav`)

Routes (`Routes.kt`, `@Serializable`):

```kotlin
data object Flights                                        // start destination
data class AddFlight(
    val prefillFlight: String? = null,
    val prefillDate: String? = null,
    val prefillPnr: String? = null,
    val prefillSource: String? = null,   // TrackSource.wire: "pdf", "screenshot", "share"; null = typed by hand
)
data class FlightDetail(val flightId: String)
data object Settings
data object ImportTicket
```

`FlyTrackNavHost` maps each route to one entry point. **These signatures are fixed**: the
NavHost calls them with named arguments, so keep the parameter names.

```kotlin
// app.flytrack.feature.flights
@Composable fun FlightsRoute(onAddFlight: () -> Unit, onOpenFlight: (flightId: String) -> Unit, onOpenSettings: () -> Unit)
@Composable fun FlightDetailRoute(flightId: String, onBack: () -> Unit)

// app.flytrack.feature.addflight
@Composable fun AddFlightRoute(prefillFlight: String?, prefillDate: String?, prefillPnr: String?,
                               onDone: (flightId: String?) -> Unit, onBack: () -> Unit,
                               prefillSource: String? = null)

// app.flytrack.feature.settings
@Composable fun SettingsRoute(onBack: () -> Unit)

// app.flytrack.feature.importticket
@Composable fun ImportTicketRoute(
    onParsed: (flight: String?, date: String?, pnr: String?, source: String?) -> Unit,
    onBack: () -> Unit,
)
```

What the callbacks do:

| Callback | Navigation |
|---|---|
| `onAddFlight` | opens `AddFlight()` |
| `onOpenFlight(id)` | opens `FlightDetail(id)` |
| `onOpenSettings` | opens `Settings` |
| `onDone(flightId)` | non-null: leaves the add/import screens and shows `FlightDetail(flightId)` above the list; null: back |
| `onParsed(flight, date, pnr, source)` | pops ImportTicket and any AddFlight under it, then opens `AddFlight(flight, date, pnr, source)`; `date` is ISO `yyyy-MM-dd`; `source` is `pdf`, `screenshot` or `share` |
| `onBack` | pops the back stack |

Each entry point gets its ViewModel with `hiltViewModel()` and forwards state and lambdas to a
stateless screen composable. Route arguments reach a ViewModel either as parameters of the Route
composable (pass them into a `LaunchedEffect` or an assisted factory) or through
`SavedStateHandle.toRoute<FlightDetail>()`.

For navigation the callbacks do not cover (for example an "Import a ticket" button on the
add-flight or flights screen), use the navigator the NavHost provides:

```kotlin
interface AppNavigator {
    fun openFlight(flightId: String)
    fun openAddFlight(prefillFlight: String? = null, prefillDate: String? = null, prefillPnr: String? = null)
    fun openImportTicket()
    fun openSettings()
    fun back()
}
val LocalAppNavigator: ProvidableCompositionLocal<AppNavigator>     // LocalAppNavigator.current
```

The flights list (top bar and empty state) and the add-flight screen open `ImportTicket` with
`LocalAppNavigator.current.openImportTicket()`. A share intent does the same.

## 14. Intents and push

`MainActivity` (`launchMode="singleTop"`) turns every intent into an `IncomingIntent` and
`resolveLaunchRequest()` (pure, tested in `LaunchRequestTest`) decides:

| Intent | Result |
|---|---|
| action `OPEN_FLIGHT`, or any intent with String extra `flight_id` holding a UUID | opens `FlightDetail(flight_id)` and calls `applyServerSync(flight_id)` |
| `ACTION_SEND` with `application/pdf`, `image/*` or `text/plain` | `SharedContentHolder.offer(...)`, opens `ImportTicket` |
| `ACTION_SEND_MULTIPLE` with `application/pdf` or `image/*` | same |
| anything else | nothing |

FCM contract (`app.flytrack.core.push.PushContract`, identical to `notify.ts`):

```kotlin
const val CHANNEL_CRITICAL = "flight_critical"     // create with IMPORTANCE_HIGH
const val CHANNEL_UPDATES  = "flight_updates"      // create with IMPORTANCE_DEFAULT
const val ACTION_OPEN_FLIGHT = "OPEN_FLIGHT"       // click_action of alert notifications
const val KEY_KIND = "kind"                        // "alert" | "sync"
const val KEY_FLIGHT_ID = "flight_id"
const val KEY_EVENT_ID = "event_id"                // alert only
const val KEY_EVENT_TYPE = "event_type"            // alert only: AlertType.wire
const val KEY_TITLE = "title"                      // alert only
const val KEY_BODY = "body"                        // alert only
const val KEY_FLIGHT = "flight"                    // sync only: compact flight JSON
const val KIND_ALERT = "alert"; const val KIND_SYNC = "sync"
fun notificationTag(flightId: String, group: String): String    // "<flightId>:<group>"
```

* **Alert** = notification message plus data. While the app is in the background or dead, Google
  Play services shows it on the channel the server names; a tap starts `MainActivity` with action
  `OPEN_FLIGHT` and the data keys as String extras. While the app is in the foreground,
  `onMessageReceived` gets it and the app must post the notification itself.
* **Sync** = data-only, collapsible. Hand `data["flight"]` to
  `FlightRepository.applyServerSync(data["flight_id"], data["flight"])`.
* For notifications the app posts itself, build the tap intent with
  `app.flytrack.ui.nav.openFlightIntent(context, flightId)`.
* Real examples of both payloads, produced by the backend's `notify.ts`:
  `app/src/test/resources/fixtures/fcm_messages.json`.

Push (`app.flytrack.push`), now real:

```kotlin
interface PushInitializer {
    val availability: StateFlow<PushAvailability>   // Checking, NotConfigured, Ready
    fun onAppStart()                                // channels, then token registration; returns immediately
    suspend fun beforeSignOut(): ActionResult?      // unregister the token while the session is still valid
}
```

`PushModule` binds `FirebasePushInitializer`. `FlyTrackMessagingService` posts foreground alerts
on the server's channel and tag, and enqueues expedited `FlightSyncWorker` for `sync` messages.
`parseIncomingPush`, `alertChannelId`, `alertTagGroup` and `notificationIdFor` are pure and tested
against `fcm_messages.json`.

There is no `google-services.json` in the repository, so Firebase is not initialised in this
build: every Firebase call is guarded with `FirebaseApp.getApps(context).isEmpty()`, and
`availability` is `NotConfigured`. `FirebaseMessaging.getInstance()` is never called in that case.

## 15. Shared content (`app.flytrack.core.share`)

```kotlin
data class SharedContent(val uris: List<String> = emptyList(), val mimeType: String? = null, val text: String? = null) {
    val isEmpty: Boolean
}
@Singleton class SharedContentHolder @Inject constructor() {
    val pending: StateFlow<SharedContent?>
    fun offer(content: SharedContent)     // called by the app shell
    fun consume(): SharedContent?         // returns the pending share and clears it
}
```

`uris` are content Uri strings (`Uri.parse(it)`). The read permission belongs to `MainActivity`
and ends with its task, so read the content while the import screen is open; do not persist the
Uris. `mimeType` can be a wildcard (`image/*`, `*/*`) for multi-shares: ask the `ContentResolver`
for each Uri's real type. `text` is `EXTRA_TEXT` (a shared e-mail or SMS body).

## 16. Strings

| File | Owner |
|---|---|
| `res/values/strings.xml` | foundation: app name, common actions (`action_back`, `action_retry`, `action_cancel`, `action_ok`, `action_save`, `action_done`, `action_close`, `action_add_flight`, `action_settings`), shared states (`state_*`), relative-time plurals, durations |
| `res/values/strings_flights.xml` | flights |
| `res/values/strings_addflight.xml` | addflight |
| `res/values/strings_import.xml` | importer |
| `res/values/strings_push.xml`, `res/values/strings_settings.xml` | push |

Create your file with a plain `<resources>` root. Prefix every name with the feature
(`flights_…`, `addflight_…`, `import_…`, `push_…`, `settings_…`) so names never collide. Use
`<plurals>` for anything with a count. The placeholder `stub_*` strings have been removed.

## 17. Manifest (already declared)

* Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS` (request it at runtime on
  Android 13+). MapLibre's two location permissions are removed on purpose.
* `MainActivity`: launcher, `OPEN_FLIGHT`, `SEND` (pdf, image, text), `SEND_MULTIPLE` (pdf, image).
* `.push.FlyTrackMessagingService` with `com.google.firebase.MESSAGING_EVENT`.
* FCM defaults: channel `flight_updates`, icon `ic_notification`, colour `@color/forest`.
* ML Kit: `com.google.mlkit.vision.DEPENDENCIES = ocr`. The Latin recogniser is bundled
  (`com.google.mlkit:text-recognition`), so OCR works offline. `PdfRenderer`, the photo picker and
  `OpenDocument` need no permission and no manifest entry.
* WorkManager's default initializer is removed (Hilt worker factory, section 9).

A feature that needs another manifest entry asks the foundation owner; do not edit the manifest.

## 18. Tests and fakes

Plain JVM tests only: JUnit 4, `kotlinx-coroutines-test`, Turbine, `ktor-client-mock`.

`app.flytrack.testing` (in `src/test/kotlin`):

| Class | Use |
|---|---|
| `TestData` | builders with defaults: `flight()`, `tracking()`, `tracked()`, `profile()`; airports `DEL`, `BOM`, `BLR`, `DXB`; `NOW`, `USER_ID`, `FLIGHT_ID` |
| `FakeClock` | `kotlin.time.Clock` you can set and `advance()` |
| `MainDispatcherRule` | `@get:Rule val main = MainDispatcherRule()` for ViewModels |
| `Fixtures.read(name)` | JSON fixtures in `src/test/resources/fixtures` |
| `FakeAuthRepository` | signed in by default; `signedOut()`, `notConfigured()`, `nextResult`, `setSession()` |
| `FakeFlightRepository` | state in `tracked` / `cachedFlights`; `setTracked(...)`; `searchResult`, `trackResult`, `actionResult`, `refreshResult`, `syncResult`; records `searches`, `trackCalls`, `untrackCalls`, `syncCalls`, `refreshCalls` |
| `FakeDeviceRepository` | `result`, `registrations`, `unregisteredTokens`, `notificationsEnabled` |
| `FakeAirportRepository` | knows `TestData`'s airports |
| `FakeProfileRepository` | `profile` (MutableStateFlow), `refreshResult` |
| `FakeSettingsStore` | in-memory |
| `FakeBackendApi`, `FakeAuthGateway`, `InMemoryDatabase` | lower layer, for repository-implementation tests; `FakeBackendApi.functionHandlers["parse-ticket"]` fakes an Edge Function |

The fakes behave sensibly by default: tracking a cached flight makes it appear in
`observeTrackedFlights()`, archiving hides it, forcing a result (`trackResult = …`) overrides that.
`FakeRepositoriesTest` documents the defaults.

A typical ViewModel test:

```kotlin
class FlightsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val flights = FakeFlightRepository().apply { setTracked(TestData.tracked()) }

    @Test fun showsTrackedFlights() = runTest {
        val vm = FlightsViewModel(flights, FakeClock())
        vm.uiState.test { /* Turbine */ }
    }
}
```

Fixtures were generated from the real schema (migration + seed on Postgres) and from the real
`notify.ts`: `flight_view_pro.json`, `flight_view_free.json`, `flight_view_minimal.json`,
`search_response.json`, `user_flights.json`, `airports.json`, `profile.json`,
`fcm_messages.json`, `search_error_*.json`, `postgrest_error_*.json`.

## 19. Backend not configured

With blank `SUPABASE_URL` / `SUPABASE_ANON_KEY` (this checkout):

* the app starts; no Supabase client is created;
* `AuthRepository.session` is `SessionState.NotConfigured`;
* every repository command returns its `NotConfigured` result;
* the app shell shows `BackendNotConfiguredBanner` above every screen;
* a screen whose main action cannot work should show `BackendNotConfiguredState()`.

`BackendConfig.isGoogleSignInConfigured` is false while `GOOGLE_WEB_CLIENT_ID` is blank: hide or
disable "Link Google account" then.

## 20. Known limits

* Nothing here has run on a device or emulator. Room's SQL is checked by Room's compiler, and the
  supabase-kt request shapes by mock-engine tests, but not against a live Supabase project.
* There is no connectivity listener: after an offline start, data refreshes on the next
  `refresh()` a screen triggers.
* `flight_events` (the alert history of a flight) has no repository yet.
* `assembleRelease` (R8) and `lintDebug` pass in CI-style Gradle runs. Nothing here has been
  installed on a device, so notification channels, Credential Manager, ML Kit and OEM battery
  deep links are untested on hardware.
* Default alert toggles in Settings apply to flights tracked afterwards. They do not rewrite
  alerts on flights the user already changed.

## 21. P3 search and social (android-search-social)

Contracts other features can rely on (docs/P3_CONTRACTS.md A1, A3, B5, B6, B10, B12, B13):

* **Tracking cap (A1).** `UserProfile.FREE_TRACKING_LIMIT = 100`, `PRO_TRACKING_LIMIT = 300` (the server's
  anti-abuse `tracking_cap`). Nothing in the app sells Pro against a flight count any more;
  `TrackProblem.LimitReached(limit)` only says "archive some flights".
* **Searches.** `FlightRepository.searchPast(flightText, date)` (search-flight `mode: "past"`,
  `PastSearchWindow`) and `searchRoute(RouteSearchQuery)` (search-route, `RouteSearchWindow`) return the
  same `SearchResult` as `search`. `TrackExtras(archived = true)` inserts a past flight with
  `archived_at = now()` and never touches an existing row.
* **Bundled airports.** `assets/airports.json` (4,000 scheduled airports, compact rows
  `[iata, name, city, country, lat, lon, tz]`, HeyTurbulence's catalog). Inject
  `feature.airports.AirportCatalog`; `AirportList.search(query)` ranks code, city, name.
* **Calendar import (A3).** `feature.calendarimport.CalendarFlightParser` is the Kotlin port of
  HeyTurbulence `calendar-parser.js`; its fixtures `src/test/resources/fixtures/calendar_events.json` are
  shared with iOS. READ_CALENDAR is requested only from the import screen's "Scan my calendar".
* **Crew mode (B12).** `feature.social.ProfileFlagsRepository.crewMode: Flow<Boolean>` (device-cached
  `profiles.crew_mode`). In Compose: `LocalCrewMode` (crew wording: block times, block hours) and
  `LocalUpsellsHidden` (every Pro teaser or "See plans" returns early when true). Both are provided by
  `ProStateProvider`. New upsell composables must check `LocalUpsellsHidden`.
* **Family (B13).** `ProState.source == ProSource.FAMILY` / `isViaFamily` / `familyOwnerName`; show
  `familyProText(ownerName)` ("Pro via Asha's family"). The grant is cached from `my_family()` and from
  `sync-entitlement`'s `via_family`.
* Routes: `CalendarImport`, `FriendPassport(friendId, friendName)`, `Family(prefillCode)`;
  `AddFlight(prefillFrom, prefillTo)` opens the "By route" tab. Navigator: `openCalendarImport`,
  `openAddFlightFromCalendar`, `openFriendPassport`, `openFamily`.

## 22. P3 Wear OS (android-wear, contract B9)

* **Module `:wear`** (`android/wear`, Kotlin package `app.flytrack.wear`, minSdk 30). The `applicationId` is `app.flytrack`, the same as the phone: the Wearable Data Layer only connects apps with the same package name and signing key, and Play pairs the watch build with the phone listing by package. Watch `versionCode`s live in the `1xxxxx` range so they never collide with the phone's.
* **Phone side:** `feature/wear/WearNextFlight` (the payload: the sync push's compact keys plus `dtz`/`atz`, the widget's status word `sw`, delay `dm`, `tone`, `est`) and `NextFlightPublisher` (`DataLayerNextFlightPublisher`, Hilt-bound). `LiveCoordinator.apply` calls it after the widget, so Room changes, FCM syncs and the tick all reach the watch. The flight is the widget's (`LiveFlightSelector.selectWidget`); `WearPublishGate` writes only when the payload changes, and an empty state deletes the item. The logic lives in `WearNextFlightSync` over a `WearDataWriter` (Play services: `DataLayerWearWriter`), unit-tested with a fake: every write is bounded with `withTimeoutOrNull` (10 s) because a `withTimeout` failure is a `CancellationException` and, rethrown, would silently end `LiveCoordinator`'s Room collector; failures are logged and retried on the next update.
* **Watch side:** `data/NextFlightRepository` reads `/next_flight` from the local Data Layer store; `ui/WatchFlightFormatter` (pure, tested) builds what is drawn and drops a stale flight with the phone's own relevance rule; `tile/NextFlightTileService` (protolayout Material 3), `complication/NextFlightComplicationService` (SHORT_TEXT, LONG_TEXT), `sync/NextFlightListenerService` (redraw requests on change) and `NextFlightActivity` (plain views).
* The payload's JSON is pinned on both sides by the same golden string (`WearNextFlightTest.GOLDEN_JSON`, `NextFlightPayloadTest.PHONE_JSON`): change both together.

## 23. P4: radar, TripIt, Gift Pro, terminal maps (android-p4, docs/P4_CONTRACTS.md 1–4)

* **Routes / navigator.** `Radar(iata?, lat?, lon?)` (lat/lon are decimal strings), `TerminalMap(iata, gate?, belt?, flightId?)`,
  `RedeemGift(prefillCode?)`, `MyGifts`; `AppNavigator.openRadar`, `openTerminalMap`, `openRedeemGift`, `openMyGifts`.
* **Radar (`feature/radar`).** `RadarRepository.nearby(RadarQuery)` → `RadarResult` (GET `radar` through supabase-kt Functions with
  `method = Get`; `BackendApi.invokeFunction` only POSTs). `RadarViewModel` refreshes every 10 s only between `setVisible(true/false)`
  (screen lifecycle), waits 60 s after `rate_limited`, stops on `feed_unavailable`/`feed_unlicensed` ("Radar is coming soon"),
  and keeps the last planes with a notice on offline/feed errors. Proximity points are rounded to 0.05° on the phone
  (`RadarQuery.around`). Entry points: "Ground radar" (`AirportMapButtons`), Settings / flight list "Planes near me", and
  "Planes around your flight" in the in-air map sheet (`PlanesAroundFlightButton`, the plane's live/estimated point). Location: `CoarseLocationProvider` (LocationManager network/passive provider, no Play services
  location), permission `ACCESS_COARSE_LOCATION` asked on tap; FINE stays removed in the manifest. The map is
  `RadarMapStyle` = `FlightMapStyle.style()` plus range/center/plane/label layers. **Labels are bitmaps**
  (`MapLabelBitmaps`): the offline styles have no glyphs, so `text-field` cannot render; use `icon-image` with images.
* **TripIt (`feature/tripit`).** `TripItSettingsSection()` in Settings → travel section. Connect opens `authorize_url` (only https
  `*.tripit.com`) in a Custom Tab built by hand (`openInCustomTab`; androidx.browser is not in the dependency set).
  The manifest routes `heygate://tripit/*` to MainActivity, which calls `TripItDeepLinks.offer(intent.dataString)`; the NavHost
  then opens Settings and the section consumes the link (Connected → status refresh, flights refreshed after the server's
  first background sync). Hidden on `not_configured`. Flights added by the server have `TrackSource.TRIPIT`.
* **Gift Pro (`feature/gift`).** `GiftStore` (`RevenueCatGiftStore` fetches `heygate_gift_year`/`heygate_gift_trip` by product id,
  INAPP, after logging RevenueCat in as the Supabase user; `NoopGiftStore` without a key). The paywall shows a "Get Pro | Gift Pro"
  tab row (`PaywallScreen(giftTab = …)`, hidden in crew mode; `GiftTabRequest.request()` opens it on the gift tab). After a
  purchase `GiftRepository.awaitNewCode` polls `my_gifts()` (~25 s) for the webhook's code, then `GiftCodeCard` offers
  WhatsApp / Share / Copy (Hinglish + English text). `redeem_gift` normalises like the server and refreshes the profile on success.
* **Terminal maps (`feature/terminalmap`).** `TerminalMapRepository` reads `assets/terminal-maps/{IATA}.geojson` (parsed once per
  process); the style reads the asset directly (`asset://terminal-maps/…`). `AirportMapButtons` under the departure and arrival
  blocks of flight detail ("Terminal map" for airports with a file or in `PLANNED_AIRPORTS`, "Ground radar"). The user's gate
  (`dep_gate`, matched by `TerminalMapLogic.normalizeRef`: "Gate 07" = "7") and the belt area are highlighted; the POI list
  sorts by distance from the gate when it is known. "© OpenStreetMap contributors (ODbL)" is drawn over the map and must stay.
* Tests: `src/test/kotlin/app/flytrack/feature/{radar,terminalmap,tripit,gift}`; fixtures in `src/test/resources/fixtures/p4/`.
