package app.flytrack.ui.nav

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.staticCompositionLocalOf
import app.flytrack.MainActivity
import app.flytrack.core.push.PushContract

/**
 * Cross-feature navigation for the cases the route callbacks do not cover (for example an
 * "Import ticket" button on the add-flight screen). Read it with `LocalAppNavigator.current`.
 * Prefer the callbacks passed to your Route composable where they exist.
 */
interface AppNavigator {
    fun openFlight(flightId: String)
    fun openAddFlight(prefillFlight: String? = null, prefillDate: String? = null, prefillPnr: String? = null)
    fun openImportTicket()
    fun openSettings()
    fun back()
}

private object NoOpNavigator : AppNavigator {
    override fun openFlight(flightId: String) = Unit
    override fun openAddFlight(prefillFlight: String?, prefillDate: String?, prefillPnr: String?) = Unit
    override fun openImportTicket() = Unit
    override fun openSettings() = Unit
    override fun back() = Unit
}

/** Provided by FlyTrackNavHost. Outside of it (previews) the navigator does nothing. */
val LocalAppNavigator = staticCompositionLocalOf<AppNavigator> { NoOpNavigator }

/**
 * The intent that opens one flight's detail screen: use it for the PendingIntent of notifications
 * the app posts itself (foreground alerts, live notification, widget). It is the same contract a
 * tapped FCM notification uses: action `OPEN_FLIGHT` + String extra `flight_id`.
 */
fun openFlightIntent(context: Context, flightId: String): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(PushContract.ACTION_OPEN_FLIGHT)
        .putExtra(PushContract.KEY_FLIGHT_ID, flightId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
