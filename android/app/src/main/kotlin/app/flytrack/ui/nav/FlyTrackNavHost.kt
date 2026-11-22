package app.flytrack.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.flytrack.feature.addflight.AddFlightRoute
import app.flytrack.feature.flights.FlightDetailRoute
import app.flytrack.feature.flights.FlightsRoute
import app.flytrack.feature.importticket.ImportTicketRoute
import app.flytrack.feature.settings.SettingsRoute

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

    override fun openSettings() {
        navController.navigate(Settings) { launchSingleTop = true }
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

    /** The importer found flight details: replace the import screen by the pre-filled add-flight screen. */
    fun openAddFlightFromImport(flight: String?, date: String?, pnr: String?) {
        navController.navigate(AddFlight(flight, date, pnr)) {
            popUpTo<ImportTicket> { inclusive = true }
            launchSingleTop = true
        }
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
    CompositionLocalProvider(LocalAppNavigator provides navigator) {
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
                    onDone = { flightId -> if (flightId != null) navigator.openFlightFromAdd(flightId) else navigator.back() },
                    onBack = { navigator.back() },
                )
            }
            composable<FlightDetail> { entry ->
                FlightDetailRoute(
                    flightId = entry.toRoute<FlightDetail>().flightId,
                    onBack = { navigator.back() },
                )
            }
            composable<Settings> {
                SettingsRoute(onBack = { navigator.back() })
            }
            composable<ImportTicket> {
                ImportTicketRoute(
                    onParsed = { flight, date, pnr -> navigator.openAddFlightFromImport(flight, date, pnr) },
                    onBack = { navigator.back() },
                )
            }
        }
    }
}
