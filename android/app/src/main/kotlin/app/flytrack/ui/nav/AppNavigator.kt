package app.flytrack.ui.nav

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.staticCompositionLocalOf
import app.flytrack.MainActivity
import app.flytrack.core.push.PushContract
import app.flytrack.feature.gmailimport.GmailSuggestion

/**
 * Cross-feature navigation for the cases the route callbacks do not cover (for example an
 * "Import ticket" button on the add-flight screen). Read it with `LocalAppNavigator.current`.
 * Prefer the callbacks passed to your Route composable where they exist.
 */
interface AppNavigator {
    fun openFlight(flightId: String)
    fun openAddFlight(prefillFlight: String? = null, prefillDate: String? = null, prefillPnr: String? = null)
    fun openImportTicket()

    /** A ticket or boarding pass was read: open add-flight filled in, replacing the screen that read it. */
    fun openAddFlightFromImport(flight: String?, date: String?, pnr: String?, source: String?, seat: String? = null)
    fun openGmailImport()

    /** A Gmail suggestion was picked (Gmail screen, Found in Gmail card, notification): add-flight filled in from it. */
    fun openAddFlightFromGmail(s: GmailSuggestion)
    fun openSettings()

    /** Passport and Year in Review (P2). */
    fun openPassport() = Unit

    /** Friends (P2 wave 2). */
    fun openFriends() = Unit

    /** "Enter a friend code" (P2 wave 2). */
    fun openAcceptFriendInvite(prefillCode: String? = null) = Unit

    /** Email forwarding address (P2 wave 2). */
    fun openEmailForwarding() = Unit

    /** P3 A3: calendar import. */
    fun openCalendarImport() = Unit

    /** P3 A3: add-flight filled in from a calendar suggestion (flight number, or route without one), source calendar. */
    fun openAddFlightFromCalendar(flight: String?, date: String, from: String?, to: String?) = Unit

    /** P3 B10: compare Passports with a friend. */
    fun openFriendPassport(friendId: String, friendName: String?) = Unit

    /** P3 B13: family sharing. */
    fun openFamily(prefillCode: String? = null) = Unit

    /** P4 radar: [iata] = ground radar; [lat]/[lon] = around a point; all null = planes near me. */
    fun openRadar(iata: String?, lat: Double? = null, lon: Double? = null) = Unit

    /** P4 terminal map of [iata], highlighting [gate] / [belt] when known. */
    fun openTerminalMap(iata: String, gate: String? = null, belt: String? = null, flightId: String? = null) = Unit

    /** P4 "Redeem a gift code". */
    fun openRedeemGift(prefillCode: String? = null) = Unit

    /** P4 "My gifts". */
    fun openMyGifts() = Unit
    fun back()
}

private object NoOpNavigator : AppNavigator {
    override fun openFlight(flightId: String) = Unit
    override fun openAddFlight(prefillFlight: String?, prefillDate: String?, prefillPnr: String?) = Unit
    override fun openImportTicket() = Unit
    override fun openAddFlightFromImport(flight: String?, date: String?, pnr: String?, source: String?, seat: String?) = Unit
    override fun openGmailImport() = Unit
    override fun openAddFlightFromGmail(s: GmailSuggestion) = Unit
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
