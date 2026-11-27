package app.flytrack.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.filterNotNull
import app.flytrack.feature.tripit.TripItDeepLinks
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.flytrack.core.model.TrackSource
import app.flytrack.feature.addflight.AddFlightRoute
import app.flytrack.feature.billing.ui.LocalProFlightId
import app.flytrack.feature.calendarimport.CalendarImportRoute
import app.flytrack.feature.calendarimport.SuggestionAction
import app.flytrack.feature.family.FamilyRoute
import app.flytrack.feature.social.FriendPassportRoute
import app.flytrack.feature.billing.ui.PaywallRoute
import app.flytrack.feature.billing.ui.ProStateProvider
import app.flytrack.feature.flights.FlightDetailRoute
import app.flytrack.feature.flights.FlightsRoute
import app.flytrack.feature.forwarding.EmailForwardingRoute
import app.flytrack.feature.friends.AcceptFriendInviteRoute
import app.flytrack.feature.friends.FriendsRoute
import app.flytrack.feature.gmailimport.GmailImportRoute
import app.flytrack.feature.gmailimport.GmailSuggestion
import app.flytrack.feature.importticket.ImportTicketRoute
import app.flytrack.feature.passport.PassportRoute
import app.flytrack.feature.settings.SettingsRoute
import app.flytrack.feature.gift.MyGiftsRoute
import app.flytrack.feature.gift.RedeemGiftRoute
import app.flytrack.feature.radar.RadarRoute
import app.flytrack.feature.terminalmap.TerminalMapRoute

/** [AppNavigator] on top of a NavController. Also used by MainActivity to act on incoming intents. */
class NavControllerNavigator(private val navController: NavHostController) : AppNavigator {
    override fun openFlight(flightId: String) {
        navController.navigate(FlightDetail(flightId)) { launchSingleTop = true }
    }

    override fun openAddFlight(prefillFlight: String?, prefillDate: String?, prefillPnr: String?) {
        navController.navigate(AddFlight(prefillFlight, prefillDate, prefillPnr)) { launchSingleTop = true }
    }

    override fun openImportTicket() {
        navController.navigate(ImportTicket) { launchSingleTop = true }
    }

    override fun openGmailImport() {
        navController.navigate(GmailImport) { launchSingleTop = true }
    }

    /** Back from add-flight returns to wherever the suggestion was picked; the suggestion stays pending. */
    override fun openAddFlightFromGmail(s: GmailSuggestion) {
        navController.navigate(
            AddFlight(
                prefillFlight = s.flight,
                prefillDate = s.date.toString(),
                prefillPnr = s.pnr,
                prefillSource = TrackSource.GMAIL.wire,
                prefillGmailSuggestionId = s.id,
            ),
        ) { launchSingleTop = true }
    }

    override fun openSettings() {
        navController.navigate(Settings) { launchSingleTop = true }
    }

    override fun openPassport() {
        navController.navigate(Passport) { launchSingleTop = true }
    }

    override fun openFriends() {
        navController.navigate(Friends) { launchSingleTop = true }
    }

    override fun openAcceptFriendInvite(prefillCode: String?) {
        navController.navigate(AcceptFriendInvite(prefillCode)) { launchSingleTop = true }
    }

    override fun openEmailForwarding() {
        navController.navigate(EmailForwarding) { launchSingleTop = true }
    }

    override fun openCalendarImport() {
        navController.navigate(CalendarImport) { launchSingleTop = true }
    }

    /** Back from add-flight returns to the suggestions, which then mark the flight as tracked. */
    override fun openAddFlightFromCalendar(flight: String?, date: String, from: String?, to: String?) {
        navController.navigate(
            AddFlight(
                prefillFlight = flight,
                prefillDate = date,
                prefillSource = TrackSource.CALENDAR.wire,
                prefillFrom = from.takeIf { flight == null },
                prefillTo = to.takeIf { flight == null },
            ),
        ) { launchSingleTop = true }
    }

    override fun openFriendPassport(friendId: String, friendName: String?) {
        navController.navigate(FriendPassport(friendId, friendName)) { launchSingleTop = true }
    }

    override fun openFamily(prefillCode: String?) {
        navController.navigate(Family(prefillCode)) { launchSingleTop = true }
    }

    override fun openRadar(iata: String?, lat: Double?, lon: Double?) {
        navController.navigate(Radar(iata, lat?.toString(), lon?.toString())) { launchSingleTop = true }
    }

    override fun openTerminalMap(iata: String, gate: String?, belt: String?, flightId: String?) {
        navController.navigate(TerminalMap(iata, gate, belt, flightId)) { launchSingleTop = true }
    }

    override fun openRedeemGift(prefillCode: String?) {
        navController.navigate(RedeemGift(prefillCode)) { launchSingleTop = true }
    }

    override fun openMyGifts() {
        navController.navigate(MyGifts) { launchSingleTop = true }
    }

    override fun back() {
        navController.popBackStack()
    }

    /** After a flight was added: leave the add/import screens and show the new flight above the list. */
    fun openFlightFromAdd(flightId: String) {
        navController.navigate(FlightDetail(flightId)) {
            popUpTo<Flights>()
            launchSingleTop = true
        }
    }

    /**
     * The importer found flight details. Drop the import screen and, when import was opened from
     * add-flight, that add-flight entry too, so Back returns to the flight list.
     */
    override fun openAddFlightFromImport(flight: String?, date: String?, pnr: String?, source: String?, seat: String?) {
        val removedAdd = navController.popBackStack<AddFlight>(inclusive = true)
        if (!removedAdd) navController.popBackStack<ImportTicket>(inclusive = true)
        navController.navigate(AddFlight(flight, date, pnr, source, seat)) { launchSingleTop = true }
    }
}

/**
 * The app's navigation graph. Each destination delegates to one feature entry point
 * (`app.flytrack.feature.*.XxxRoute`); features never reference the NavController.
 */
@Composable
fun FlyTrackNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    navigator: NavControllerNavigator = remember(navController) { NavControllerNavigator(navController) },
) {
    // P4: a TripIt redirect (heygate://tripit/...) shows Settings, where the TripIt section reads it.
    LaunchedEffect(navigator) {
        TripItDeepLinks.pending.filterNotNull().collect { navigator.openSettings() }
    }
    CompositionLocalProvider(LocalAppNavigator provides navigator) {
        ProStateProvider(onOpenPaywall = { navController.navigate(Paywall) { launchSingleTop = true } }) {
            NavHost(navController = navController, startDestination = Flights, modifier = modifier) {
                composable<Flights> {
                    FlightsRoute(
                        onAddFlight = { navigator.openAddFlight() },
                        onOpenFlight = { flightId -> navigator.openFlight(flightId) },
                        onOpenSettings = { navigator.openSettings() },
                    )
                }
                composable<AddFlight> { entry ->
                    val route = entry.toRoute<AddFlight>()
                    AddFlightRoute(
                        prefillFlight = route.prefillFlight,
                        prefillDate = route.prefillDate,
                        prefillPnr = route.prefillPnr,
                        prefillSource = route.prefillSource,
                        prefillSeat = route.prefillSeat,
                        prefillGmailSuggestionId = route.prefillGmailSuggestionId,
                        prefillFrom = route.prefillFrom,
                        prefillTo = route.prefillTo,
                        onDone = { flightId -> if (flightId != null) navigator.openFlightFromAdd(flightId) else navigator.back() },
                        onBack = { navigator.back() },
                    )
                }
                composable<FlightDetail> { entry ->
                    val flightId = entry.toRoute<FlightDetail>().flightId
                    CompositionLocalProvider(LocalProFlightId provides flightId) {
                        FlightDetailRoute(
                            flightId = flightId,
                            onBack = { navigator.back() },
                        )
                    }
                }
                composable<Settings> {
                    SettingsRoute(onBack = { navigator.back() })
                }
                composable<ImportTicket> {
                    ImportTicketRoute(
                        onParsed = { flight, date, pnr, source -> navigator.openAddFlightFromImport(flight, date, pnr, source) },
                        onBack = { navigator.back() },
                    )
                }
                composable<Passport> {
                    PassportRoute(onBack = { navigator.back() })
                }
                composable<Friends> {
                    FriendsRoute(onEnterCode = { navigator.openAcceptFriendInvite() }, onBack = { navigator.back() })
                }
                composable<AcceptFriendInvite> { entry ->
                    AcceptFriendInviteRoute(
                        prefillCode = entry.toRoute<AcceptFriendInvite>().prefillCode,
                        onDone = { navigator.back() },
                        onBack = { navigator.back() },
                    )
                }
                composable<EmailForwarding> {
                    EmailForwardingRoute(onBack = { navigator.back() })
                }
                composable<CalendarImport> {
                    CalendarImportRoute(
                        onAdd = { action ->
                            when (action) {
                                is SuggestionAction.ByNumber ->
                                    navigator.openAddFlightFromCalendar(action.flight, action.date.toString(), null, null)
                                is SuggestionAction.ByRoute ->
                                    navigator.openAddFlightFromCalendar(null, action.date.toString(), action.from, action.to)
                                SuggestionAction.NeedsDetails -> Unit
                            }
                        },
                        onBack = { navigator.back() },
                    )
                }
                composable<FriendPassport> { entry ->
                    val route = entry.toRoute<FriendPassport>()
                    FriendPassportRoute(friendId = route.friendId, friendName = route.friendName, onBack = { navigator.back() })
                }
                composable<Family> { entry ->
                    FamilyRoute(prefillCode = entry.toRoute<Family>().prefillCode, onBack = { navigator.back() })
                }
                composable<Paywall> {
                    PaywallRoute(onBack = { navigator.back() })
                }
                // P4: radar, terminal maps, Gift Pro.
                composable<Radar> { entry ->
                    val route = entry.toRoute<Radar>()
                    RadarRoute(iata = route.iata, lat = route.lat?.toDoubleOrNull(), lon = route.lon?.toDoubleOrNull(), onBack = { navigator.back() })
                }
                composable<TerminalMap> { entry ->
                    val route = entry.toRoute<TerminalMap>()
                    TerminalMapRoute(
                        iata = route.iata,
                        gate = route.gate,
                        belt = route.belt,
                        flightId = route.flightId,
                        onBack = { navigator.back() },
                    )
                }
                composable<RedeemGift> { entry ->
                    RedeemGiftRoute(prefillCode = entry.toRoute<RedeemGift>().prefillCode, onBack = { navigator.back() })
                }
                composable<MyGifts> {
                    MyGiftsRoute(onBack = { navigator.back() })
                }
                composable<GmailImport> {
                    GmailImportRoute(
                        onOpenSuggestion = navigator::openAddFlightFromGmail,
                        onImportManually = navigator::openImportTicket,
                        onTypeInstead = { navigator.openAddFlight() },
                        onBack = { navigator.back() },
                    )
                }
            }
        }
    }
}
