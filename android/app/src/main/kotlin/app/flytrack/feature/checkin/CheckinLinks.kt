package app.flytrack.feature.checkin

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** FCM data key of CHECKIN_OPEN / CHECKIN_CLOSING alerts (P1 contract, section 3); also a String intent extra after a tap. */
const val KEY_CHECKIN_URL = "checkin_url"

/** Event types whose tap opens the airline's page instead of only the flight. */
const val CHECKIN_EVENT_OPEN = "CHECKIN_OPEN"
const val CHECKIN_EVENT_CLOSING = "CHECKIN_CLOSING"
val CHECKIN_EVENT_TYPES = setOf(CHECKIN_EVENT_OPEN, CHECKIN_EVENT_CLOSING)

/**
 * The airline page a tapped notification asks for: only for a check-in alert and only an https
 * link. Pure, so the decision is unit-tested; MainActivity's extras are untrusted input.
 */
fun checkinUrlFromPush(eventType: String?, checkinUrl: String?): String? {
    if (eventType?.trim() !in CHECKIN_EVENT_TYPES) return null
    return safeCheckinUrl(checkinUrl)
}

/**
 * Opens the airline's web check-in page in the browser. There is no Custom Tabs library in this
 * build, so a plain VIEW intent is used (Chrome itself may show it as a tab).
 *
 * @return false when the link is not https or no browser is installed
 */
fun openCheckinPage(context: Context, url: String?): Boolean {
    val safe = safeCheckinUrl(url) ?: return false
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safe))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Tap on a check-in alert: opens the airline's page. Call with the String extras of the launch intent. */
fun openCheckinPageFromPush(context: Context, eventType: String?, checkinUrl: String?): Boolean =
    checkinUrlFromPush(eventType, checkinUrl)?.let { openCheckinPage(context, it) } == true
