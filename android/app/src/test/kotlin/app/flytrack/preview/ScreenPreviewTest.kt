package app.flytrack.preview

import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.flytrack.core.model.Airport
import app.flytrack.core.model.AlertType
import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightStatus
import app.flytrack.data.repo.Appearance
import app.flytrack.feature.addflight.AddFlightScreen
import app.flytrack.feature.addflight.AddFlightUiState
import app.flytrack.feature.addflight.SearchQuery
import app.flytrack.feature.addflight.SearchUiState
import app.flytrack.feature.airporthealth.AirportHealth
import app.flytrack.feature.airporthealth.AirportHealthEntry
import app.flytrack.feature.airporthealth.AirportHealthUiState
import app.flytrack.feature.airporthealth.AirportHealthViewModel
import app.flytrack.feature.airporthealth.AirportWeather
import app.flytrack.feature.airporthealth.FlightCategory
import app.flytrack.feature.airporthealth.HealthDetail
import app.flytrack.feature.airporthealth.HealthLevel
import app.flytrack.feature.airporthealth.HealthSide
import app.flytrack.feature.billing.PlanKind
import app.flytrack.feature.billing.PlanPricing
import app.flytrack.feature.billing.ProSource
import app.flytrack.feature.billing.ProState
import app.flytrack.feature.billing.StorePrice
import app.flytrack.feature.billing.ui.LocalProState
import app.flytrack.feature.billing.ui.PaywallContent
import app.flytrack.feature.billing.ui.PaywallScreen
import app.flytrack.feature.billing.ui.PaywallUiState
import app.flytrack.feature.billing.ui.PlanRow
import app.flytrack.feature.billing.ui.ProSettingsContent
import app.flytrack.feature.billing.ui.ProSettingsUiState
import app.flytrack.feature.calendar.AddToCalendarViewModel
import app.flytrack.feature.checkin.CheckinUiModel
import app.flytrack.feature.checkin.CheckinWindow
import app.flytrack.feature.flights.FlightDetailScreen
import app.flytrack.feature.flights.FlightDetailUiState
import app.flytrack.feature.flights.FlightsScreen
import app.flytrack.feature.flights.FlightsUiState
import app.flytrack.feature.flights.presentation.FlightDetailUiModel
import app.flytrack.feature.flights.ui.PreviewFlights
import app.flytrack.feature.forecast.ArrivalForecast
import app.flytrack.feature.forecast.ForecastConfidence
import app.flytrack.feature.forecast.ForecastHistory
import app.flytrack.feature.forecast.ForecastMethod
import app.flytrack.feature.forecast.ForecastUiState
import app.flytrack.feature.forecast.ForecastViewModel
import app.flytrack.feature.friends.Friend
import app.flytrack.feature.friends.FriendFlight
import app.flytrack.feature.friends.FriendsFlightsUiState
import app.flytrack.feature.friends.FriendsLoad
import app.flytrack.feature.friends.FriendsScreen
import app.flytrack.feature.friends.FriendsUiState
import app.flytrack.feature.friends.ShareWithFriendsUiState
import app.flytrack.feature.friends.ShareWithFriendsViewModel
import app.flytrack.feature.friends.friendFlightCards
import app.flytrack.feature.leave.LeavePlan
import app.flytrack.feature.leave.WhenToLeaveUiState
import app.flytrack.feature.leave.WhenToLeaveViewModel
import app.flytrack.feature.passport.CountedAircraft
import app.flytrack.feature.passport.CountedAirline
import app.flytrack.feature.passport.CountedAirport
import app.flytrack.feature.passport.DelayStats
import app.flytrack.feature.passport.LongestFlight
import app.flytrack.feature.passport.MonthCount
import app.flytrack.feature.passport.Passport
import app.flytrack.feature.passport.PassportContent
import app.flytrack.feature.passport.PassportScope
import app.flytrack.feature.passport.PassportScreen
import app.flytrack.feature.passport.PassportTab
import app.flytrack.feature.passport.PassportUiState
import app.flytrack.feature.passport.TopRoute
import app.flytrack.feature.passport.WorstDelay
import app.flytrack.feature.settings.AccountUi
import app.flytrack.feature.settings.AlertLanguage
import app.flytrack.feature.settings.AlertPrefs
import app.flytrack.feature.settings.AlertPrefsContent
import app.flytrack.feature.settings.AlertPrefsUiState
import app.flytrack.feature.settings.PassportSettingsEntry
import app.flytrack.feature.settings.SettingsScreen
import app.flytrack.feature.settings.SettingsUiState
import app.flytrack.feature.settings.alertToggles
import app.flytrack.push.ChannelHealth
import app.flytrack.push.DeviceMaker
import app.flytrack.push.NotificationHealth
import app.flytrack.push.PermissionHealth
import app.flytrack.push.PushAvailability
import app.flytrack.ui.theme.FlyTrackTheme
import dagger.hilt.android.internal.builders.ViewModelComponentBuilder
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import java.lang.reflect.Proxy
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalTime
import java.util.Locale
import kotlin.time.Instant

/**
 * Renders the real screens (stateless composables, real theme, sample data) to PNG files in
 * docs/screenshots/preview, so the app can be looked at without a device. Run with:
 * `./gradlew :app:testDebugUnitTest --tests 'app.flytrack.preview.*'`.
 *
 * A few blocks of the flight detail screen fetch their own data through `hiltViewModel()`; they get
 * Mockito stand-ins whose `uiState` is a fixed sample, provided through [LocalViewModelStoreOwner].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE)
class ScreenPreviewTest {

    @get:Rule
    val compose = createComposeRule()

    // ---- 1. Flights list ------------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "$PHONE_W-h1500dp-xxhdpi")
    fun flightsListLight() = shoot("01-flights-list-light") { FlightsList(PreviewFlights.listState()) }

    @Test
    @Config(qualifiers = "$PHONE_W-h1500dp-xxhdpi")
    fun flightsListDark() = shoot("01-flights-list-dark", dark = true) { FlightsList(PreviewFlights.listState()) }

    // ---- 2. Flight detail (free user) -----------------------------------------------------------------

    @Test
    @Config(qualifiers = "$PHONE_W-h3750dp-xxhdpi")
    fun flightDetailLight() = shoot("02-flight-detail-light", owner = detailViewModels(pro = false)) {
        Detail(PreviewFlights.detail(), checkin())
    }

    @Test
    @Config(qualifiers = "$PHONE_W-h3750dp-xxhdpi")
    fun flightDetailDark() = shoot("02-flight-detail-dark", dark = true, owner = detailViewModels(pro = false)) {
        Detail(PreviewFlights.detail(), checkin())
    }

    // ---- 3. Flight detail (Pro) -----------------------------------------------------------------------

    @Test
    @Config(qualifiers = "$PHONE_W-h4050dp-xxhdpi")
    fun flightDetailPro() = shoot("03-flight-detail-pro", pro = PRO, owner = detailViewModels(pro = true)) {
        Detail(PreviewFlights.detailPro(), checkin())
    }

    // ---- 4. Paywall -----------------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "$PHONE_W-h1000dp-xxhdpi")
    fun paywall() = shoot("04-paywall") {
        val inr = Locale.forLanguageTag("en-IN")
        val annual = StorePrice("₹4,999", 4_999_000_000L, "INR")
        val monthly = StorePrice("₹499", 499_000_000L, "INR")
        PaywallScreen(
            state = PaywallUiState(
                content = PaywallContent.Plans(
                    listOf(
                        PlanRow(
                            PlanKind.ANNUAL, annual.formatted,
                            perMonth = PlanPricing.monthlyEquivalent(annual, inr),
                            savePercent = PlanPricing.annualSavingPercent(annual, monthly),
                        ),
                        PlanRow(PlanKind.MONTHLY, monthly.formatted),
                        PlanRow(PlanKind.TRIP_PASS, "₹99"),
                    ),
                ),
                selected = PlanKind.ANNUAL,
            ),
            onBack = {}, onSelect = {}, onPurchase = {}, onRestore = {}, onRetry = {}, onMessageShown = {},
        )
    }

    // ---- 5. Passport / Year in Review -----------------------------------------------------------------

    @Test
    @Config(qualifiers = "$PHONE_W-h2100dp-xxhdpi")
    fun passportYearInReview() = shoot("05-passport-year-in-review") {
        PassportScreen(
            state = PassportUiState(
                tab = PassportTab.YEAR,
                currentYear = 2026,
                today = java.time.LocalDate.of(2026, 11, 25),
                content = PassportContent.Loaded(samplePassport(PassportScope.YEAR)),
            ),
            overviewAllTime = false,
            onBack = {}, onTab = {}, onRefresh = {}, onUpgrade = {},
        )
    }

    @Test
    @Config(qualifiers = "$PHONE_W-h2200dp-xxhdpi")
    fun passportOverview() = shoot("05b-passport-overview") {
        PassportScreen(
            state = PassportUiState(
                tab = PassportTab.OVERVIEW,
                currentYear = 2026,
                today = java.time.LocalDate.of(2026, 11, 25),
                content = PassportContent.Loaded(samplePassport(PassportScope.LAST_12_MONTHS).copy(proLimited = true)),
            ),
            overviewAllTime = false,
            onBack = {}, onTab = {}, onRefresh = {}, onUpgrade = {},
        )
    }

    // ---- 6. Add flight --------------------------------------------------------------------------------

    @Test
    fun addFlightIdle() = shoot("06-add-flight") {
        AddFlightScreen(state = addFlightState(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    @Config(qualifiers = "$PHONE_W-h1100dp-xxhdpi")
    fun addFlightResults() = shoot("06b-add-flight-results") {
        val state = addFlightState().copy(
            flightInput = "6E 2341",
            normalisedFlight = "6E 2341",
            pnrInput = "QX7RTL",
            seatInput = "14C",
            search = SearchUiState.Results(SearchQuery("6E 2341", LocalDate(2026, 11, 27)), listOf(searchLeg)),
        )
        AddFlightScreen(state = state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }

    // ---- 7. Settings ----------------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "$PHONE_W-h2000dp-xxhdpi")
    fun settings() = shoot("07-settings") {
        SettingsScreen(
            state = SettingsUiState(
                account = AccountUi.Google("you@example.com"),
                googleConfigured = true,
                alerts = alertToggles(muted = setOf(AlertType.DEPARTED, AlertType.LANDED), isPro = false),
                cloudConsent = true,
                health = NotificationHealth(
                    permission = PermissionHealth.GRANTED,
                    criticalChannel = ChannelHealth.OK,
                    updatesChannel = ChannelHealth.OK,
                    batteryUnrestricted = false,
                    maker = DeviceMaker.entries.first(),
                    pushConfigured = true,
                ),
                push = PushAvailability.Ready,
                versionName = "0.1.0",
            ),
            onBack = {}, onLinkGoogle = {}, onSignInWithGoogle = {}, onSignOut = {}, onAlertGroup = { _, _ -> },
            onCloudConsent = {}, onOpenNotifications = {}, onOpenCriticalChannel = {}, onOpenUpdatesChannel = {},
            onOpenBattery = {}, onMessageShown = {},
            appearance = Appearance.SYSTEM, onAppearance = {},
            onGmailAutoCheck = {}, onReconnectGmail = {}, onDisconnectGmail = {},
            proSection = {
                ProSettingsContent(pro = ProState.Free, state = ProSettingsUiState(), billingAvailable = true, onGoPro = {}, onRestore = {})
            },
            travelSection = { PassportSettingsEntry() },
            alertExtrasSection = {
                AlertPrefsContent(
                    state = AlertPrefsUiState(
                        prefs = AlertPrefs(quietHoursEnabled = true, quietStart = LocalTime.of(23, 0), quietEnd = LocalTime.of(7, 0), language = AlertLanguage.HINGLISH),
                        loading = false,
                    ),
                    onQuietEnabled = {}, onQuietStart = {}, onQuietEnd = {}, onLanguage = {},
                )
            },
        )
    }

    // ---- 8. Friends -----------------------------------------------------------------------------------

    @Test
    fun friends() = shoot("08-friends") {
        FriendsScreen(
            state = FriendsUiState(
                load = FriendsLoad.LOADED,
                inviteCode = "ABCD2345",
                friends = listOf(
                    Friend("f1", "Asha Iyer", true, Instant.parse("2026-11-20T10:00:00Z")),
                    Friend("f2", "Rohan (Papa)", true, Instant.parse("2026-10-02T10:00:00Z")),
                    Friend("f3", "Meera", false, Instant.parse("2026-09-14T10:00:00Z")),
                    Friend("f4", null, false, null),
                ),
            ),
            onBack = {}, onRefresh = {}, onInvite = {}, onEnterCode = {}, onAlerts = { _, _ -> }, onRemove = {}, onMessageShown = {},
        )
    }

    @Test
    @Config(qualifiers = "$PHONE_W-h2150dp-xxhdpi")
    fun friendsFlightsOnHome() = shoot("08b-flights-list-with-friends-flights") {
        FlightsList(
            PreviewFlights.listState(),
            FriendsFlightsUiState(friendFlightCards(friendFlights, NOW)),
        )
    }

    // ---- 9. Hindi -------------------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "hi-$PHONE_W-h1550dp-xxhdpi")
    fun flightsListHindi() = shoot("09-flights-list-hindi") { FlightsList(PreviewFlights.listState()) }

    @Test
    @Config(qualifiers = "hi-$PHONE_W-h3850dp-xxhdpi")
    fun flightDetailHindi() = shoot("09b-flight-detail-hindi", owner = detailViewModels(pro = false)) {
        Detail(PreviewFlights.detail(), checkin())
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private fun shoot(
        name: String,
        dark: Boolean = false,
        pro: ProState = ProState.Free,
        owner: FakeViewModels = FakeViewModels(emptyMap()),
        content: @Composable () -> Unit,
    ) {
        System.setProperty("roborazzi.test.record", "true")
        compose.setContent {
            val context = LocalContext.current
            val hiltContext = remember { PreviewHiltActivity(context) }
            CompositionLocalProvider(
                LocalContext provides hiltContext,
                LocalViewModelStoreOwner provides owner,
                LocalProState provides pro,
            ) {
                FlyTrackTheme(darkTheme = dark) { content() }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outputDir(), "$name.png"))
    }

    private fun outputDir(): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null && !File(dir, "docs").isDirectory) dir = dir.parentFile
        return File(dir ?: error("docs/ not found"), "docs/screenshots/preview").apply { mkdirs() }
    }

    @Composable
    private fun FlightsList(state: FlightsUiState, friends: FriendsFlightsUiState = FriendsFlightsUiState()) {
        FlightsScreen(
            state = state,
            onAddFlight = {}, onImportTicket = {}, onOpenFlight = {}, onOpenSettings = {}, onRefresh = {},
            onArchive = {}, onUntrack = {}, onUndo = {}, onMessageShown = {},
            friendsFlights = friends,
        )
    }

    @Composable
    private fun Detail(detail: FlightDetailUiModel, checkin: CheckinUiModel?) {
        FlightDetailScreen(
            state = FlightDetailUiState(isLoading = false, detail = detail, checkin = checkin),
            onBack = {}, onRefresh = {}, onAlertChange = { _, _ -> }, onTrack = {}, onArchiveChange = {},
            onUntrack = {}, onEditBooking = {}, onPnrChange = {}, onSeatChange = {}, onSaveBooking = {},
            onDismissBookingEditor = {}, onMessageShown = {}, onCabinChange = {}, onShare = {}, onShareHandled = {},
        )
    }

    private fun checkin() = CheckinUiModel(
        CheckinWindow.Open(Instant.parse("2026-11-23T12:55:00Z"), Instant.parse("2026-11-25T12:35:00Z"), closingSoon = true),
        "https://www.goindigo.in/web-check-in.html", verified = true, depTz = "Asia/Kolkata", now = NOW,
    )

    /** Stand-ins for the detail screen's self-loading blocks. */
    private fun detailViewModels(pro: Boolean): FakeViewModels {
        val health = AirportHealthUiState(
            listOf(
                AirportHealthEntry(
                    HealthSide.DEPARTURE,
                    AirportHealth(
                        "DEL", HealthLevel.BUSY, "Fog: visibility 800 m", NOW,
                        detail = if (pro) {
                            HealthDetail(
                                avgDepDelayMin = 27, delayedPct = 41, cancelledToday = 3, sampleSize = 64,
                                weather = AirportWeather(800, FlightCategory.IFR, fog = true, windKts = 4, wx = "FG", observedAt = NOW),
                            )
                        } else {
                            null
                        },
                    ),
                ),
                AirportHealthEntry(
                    HealthSide.ARRIVAL,
                    AirportHealth(
                        "BOM", HealthLevel.NORMAL, "Normal operations", NOW,
                        detail = if (pro) HealthDetail(9, 12, 0, 48, AirportWeather(6000, FlightCategory.VFR, false, 9, "HZ", NOW)) else null,
                    ),
                ),
            ),
        )
        val forecast = if (pro) {
            ForecastUiState.Ready(
                history = ForecastHistory(samples = 23, windowDays = 60, onTimePct = 61, avgDepDelayMin = 14, medianArrDelayMin = 6, cancelled = 1),
                forecast = ArrivalForecast(
                    Instant.parse("2026-11-25T15:41:00Z"), Instant.parse("2026-11-25T15:56:00Z"),
                    ForecastMethod.HISTORY, ForecastConfidence.HIGH,
                ),
                arrTz = "Asia/Kolkata",
                scheduledArrival = Instant.parse("2026-11-25T15:10:00Z"),
            )
        } else {
            ForecastUiState.Locked
        }
        val leave = if (pro) {
            WhenToLeaveUiState.Ready(
                plan = LeavePlan(
                    leaveBy = Instant.parse("2026-11-25T10:20:00Z"),
                    departure = Instant.parse("2026-11-25T13:20:00Z"),
                    depTz = "Asia/Kolkata",
                    travelMinutes = 60, bufferMinutes = 120, international = false, fog = true, peak = true,
                ),
                depIata = "DEL", depCity = "New Delhi",
                directionsUrl = "https://www.google.com/maps/dir/?api=1&destination=DEL",
                reminderAhead = false,
            )
        } else {
            WhenToLeaveUiState.Locked
        }
        return FakeViewModels(
            mapOf(
                AirportHealthViewModel::class.java to { stub<AirportHealthViewModel> { Mockito.`when`(it.uiState).thenReturn(MutableStateFlow(health)) } },
                ForecastViewModel::class.java to { stub<ForecastViewModel> { Mockito.`when`(it.uiState).thenReturn(MutableStateFlow(forecast)) } },
                WhenToLeaveViewModel::class.java to { stub<WhenToLeaveViewModel> { Mockito.`when`(it.uiState).thenReturn(MutableStateFlow(leave)) } },
                ShareWithFriendsViewModel::class.java to {
                    stub<ShareWithFriendsViewModel> { Mockito.`when`(it.uiState).thenReturn(MutableStateFlow(ShareWithFriendsUiState(share = true))) }
                },
                app.flytrack.feature.intel.FlightIntelViewModel::class.java to {
                    stub<app.flytrack.feature.intel.FlightIntelViewModel> {
                        Mockito.`when`(it.uiState).thenReturn(MutableStateFlow(app.flytrack.feature.intel.FlightIntelUiState()))
                    }
                },
                AddToCalendarViewModel::class.java to {
                    stub<AddToCalendarViewModel> { Mockito.`when`(it.flight).thenReturn(MutableStateFlow(delayedFlight)) }
                },
            ),
        )
    }

    private inline fun <reified T : ViewModel> stub(setup: (T) -> Unit): T = Mockito.mock(T::class.java).also(setup)
}

/**
 * `hiltViewModel()` wraps the owner's factory in Hilt's, which needs an activity that is a Hilt component holder.
 * This one answers with an empty set of Hilt view model keys, so every request falls through to [FakeViewModels].
 */
@SuppressLint("Registered")
class PreviewHiltActivity(base: Context) : ComponentActivity(), GeneratedComponentManager<Any> {
    init {
        attachBaseContext(base)
    }

    override fun generatedComponent(): Any = component

    private companion object {
        val component: Any = run {
            val entryPoint = Class.forName("dagger.hilt.android.internal.lifecycle.HiltViewModelFactory\$ActivityCreatorEntryPoint")
            val builder = Proxy.newProxyInstance(
                ViewModelComponentBuilder::class.java.classLoader,
                arrayOf(ViewModelComponentBuilder::class.java),
            ) { _, method, _ -> error("Preview stand-ins never build a Hilt ViewModelComponent (${method.name})") }
            Proxy.newProxyInstance(entryPoint.classLoader, arrayOf(entryPoint, GeneratedComponent::class.java)) { proxy, method, args ->
                when (method.name) {
                    "getViewModelKeys" -> emptyMap<Class<*>, Boolean>()
                    "getViewModelComponentBuilder" -> builder
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "PreviewHiltComponent"
                    else -> error("Unexpected ${method.name}")
                }
            }
        }
    }
}

/** A [ViewModelStoreOwner] whose factory hands out prepared stand-ins instead of Hilt view models. */
class FakeViewModels(private val makers: Map<Class<out ViewModel>, () -> ViewModel>) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val viewModelStore = ViewModelStore()

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            (makers[modelClass] ?: error("No preview stand-in for ${modelClass.name}")).invoke() as T
    }

    override val defaultViewModelCreationExtras: CreationExtras get() = MutableCreationExtras()
}

private const val PHONE_W = "w411dp"
private const val PHONE = "w411dp-h891dp-xxhdpi"
private val NOW = Instant.parse("2026-11-25T12:10:00Z")

private val del = Airport("DEL", "Indira Gandhi International Airport", "New Delhi", "Asia/Kolkata", 28.5665, 77.1031)
private val bom = Airport("BOM", "Chhatrapati Shivaji Maharaj International Airport", "Mumbai", "Asia/Kolkata", 19.0887, 72.8679)
private val blr = Airport("BLR", "Kempegowda International Airport", "Bengaluru", "Asia/Kolkata", 13.1979, 77.7063)
private val goi = Airport("GOX", "Manohar International Airport", "Goa", "Asia/Kolkata", 15.7300, 73.8600)
private val ccu = Airport("CCU", "Netaji Subhas Chandra Bose International Airport", "Kolkata", "Asia/Kolkata", 22.6547, 88.4467)

private fun flight(
    id: String, carrier: String, number: String, airline: String, date: String, from: Airport, to: Airport,
    status: FlightStatus, schedDep: String, schedArr: String,
    estDep: String? = null, actDep: String? = null, estArr: String? = null, actArr: String? = null,
    depTerminal: String? = null, depGate: String? = null,
) = Flight(
    id = id, carrierIata = carrier, flightNumber = number, flightDate = LocalDate.parse(date),
    depIata = from.iata, arrIata = to.iata, airlineName = airline, status = status,
    schedDep = Instant.parse(schedDep), estDep = estDep?.let(Instant::parse), actDep = actDep?.let(Instant::parse),
    depTerminal = depTerminal, depGate = depGate, depCheckin = null,
    schedArr = Instant.parse(schedArr), estArr = estArr?.let(Instant::parse), actArr = actArr?.let(Instant::parse),
    arrTerminal = null, arrGate = null, baggageBelt = null,
    aircraftModel = "Airbus A320neo", aircraftReg = null, delayReason = null,
    live = null, lastPolledAt = NOW, updatedAt = NOW,
    depAirport = from, arrAirport = to, inboundFlightId = null, prediction = null, hasProInsight = false,
)

private val delayedFlight = flight(
    "preview-delayed", "6E", "2341", "IndiGo", "2026-11-25", del, bom, FlightStatus.CHECK_IN,
    "2026-11-25T12:55:00Z", "2026-11-25T15:10:00Z", estDep = "2026-11-25T13:20:00Z", estArr = "2026-11-25T15:35:00Z",
    depTerminal = "1", depGate = "42B",
)

private val searchLeg = flight(
    "search-6e2341", "6E", "2341", "IndiGo", "2026-11-27", del, bom, FlightStatus.SCHEDULED,
    "2026-11-27T12:55:00Z", "2026-11-27T15:10:00Z", depTerminal = "1",
)

private val friendFlights = listOf(
    FriendFlight(
        "f1", "Asha Iyer",
        flight(
            "friend-1", "QP", "1104", "Akasa Air", "2026-11-25", bom, goi, FlightStatus.EN_ROUTE,
            "2026-11-25T11:30:00Z", "2026-11-25T12:40:00Z", actDep = "2026-11-25T11:41:00Z", estArr = "2026-11-25T12:47:00Z",
        ),
    ),
    FriendFlight(
        "f2", "Rohan (Papa)",
        flight(
            "friend-2", "AI", "763", "Air India", "2026-11-25", ccu, del, FlightStatus.SCHEDULED,
            "2026-11-25T14:15:00Z", "2026-11-25T16:40:00Z", depTerminal = "2",
        ),
    ),
)

private fun addFlightState() = AddFlightUiState(
    date = LocalDate(2026, 11, 27),
    today = LocalDate(2026, 11, 25),
    firstSelectableDate = LocalDate(2026, 11, 24),
    lastSelectableDate = LocalDate(2027, 11, 25),
)

private fun samplePassport(scope: PassportScope) = Passport(
    scope = scope,
    year = 2026,
    proLimited = false,
    flights = 23,
    distanceKm = 31_480,
    airborneMinutes = 2_870,
    airports = listOf(
        CountedAirport("DEL", 14), CountedAirport("BOM", 9), CountedAirport("BLR", 7),
        CountedAirport("DXB", 4), CountedAirport("GOX", 3), CountedAirport("CCU", 2),
    ),
    countries = listOf("IN", "AE", "SG"),
    airlines = listOf(
        CountedAirline("6E", "IndiGo", 11), CountedAirline("AI", "Air India", 6),
        CountedAirline("EK", "Emirates", 4), CountedAirline("QP", "Akasa Air", 2),
    ),
    aircraft = listOf(CountedAircraft("Airbus A320neo", 9), CountedAircraft("Airbus A321neo", 7), CountedAircraft("Boeing 777-300ER", 4)),
    longest = LongestFlight(null, "SQ", "403", "DEL", "SIN", 4_150),
    topRoute = TopRoute("DEL", "BOM", 6),
    delay = DelayStats(totalMin = 412, onTimePct = 68, worst = WorstDelay("6E", "2341", 145)),
    co2Kg = 3_940,
    co2Method = "distance_economy",
    byMonth = listOf(
        MonthCount("2026-01", 2), MonthCount("2026-02", 1), MonthCount("2026-03", 3), MonthCount("2026-04", 2),
        MonthCount("2026-05", 4), MonthCount("2026-06", 1), MonthCount("2026-07", 0), MonthCount("2026-08", 2),
        MonthCount("2026-09", 1), MonthCount("2026-10", 3), MonthCount("2026-11", 4),
    ),
    firstFlightDate = "2026-01-06",
)

private val PRO = ProState(
    isPro = true,
    proUntil = Instant.parse("2027-11-25T00:00:00Z"),
    source = ProSource.SUBSCRIPTION,
    confirmedByServer = true,
    trialFlightId = null,
    subscriptionActive = true,
)
