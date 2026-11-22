package app.flytrack.ui.nav

import app.flytrack.core.push.PushContract
import app.flytrack.core.share.SharedContent

/** The parts of an Android Intent the app reacts to, as plain values so the routing is JVM-testable. */
data class IncomingIntent(
    val action: String? = null,
    val mimeType: String? = null,
    /** String extra `flight_id` (set by FCM for tapped alerts and by [openFlightIntent]). */
    val flightId: String? = null,
    /** EXTRA_TEXT of a share. */
    val text: String? = null,
    /** EXTRA_STREAM Uri(s) of a share, as strings. */
    val streamUris: List<String> = emptyList(),
)

/** What an incoming intent asks the app to show. */
sealed interface LaunchRequest {
    data class OpenFlight(val flightId: String) : LaunchRequest
    data class ImportShared(val content: SharedContent) : LaunchRequest
}

internal const val ACTION_SEND = "android.intent.action.SEND"
internal const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

// MainActivity is exported, so every extra is untrusted input: only a well-formed UUID becomes a route argument.
private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

private fun isImportableType(mimeType: String?): Boolean =
    mimeType == null || mimeType == "application/pdf" || mimeType == "text/plain" ||
        mimeType.startsWith("image/") || mimeType == "*/*"

/**
 * Decides what an intent means:
 * - action [PushContract.ACTION_OPEN_FLIGHT], or any intent carrying a valid `flight_id` extra
 *   (a tapped FCM notification delivers its data keys as extras) -> open that flight;
 * - ACTION_SEND / ACTION_SEND_MULTIPLE with a PDF, image(s) or text -> import a ticket;
 * - anything else (plain launcher start) -> null.
 */
fun resolveLaunchRequest(intent: IncomingIntent): LaunchRequest? {
    val flightId = intent.flightId?.trim()
    if (flightId != null && UUID_REGEX.matches(flightId)) return LaunchRequest.OpenFlight(flightId)
    if (intent.action == PushContract.ACTION_OPEN_FLIGHT) return null // malformed or missing id: just open the app

    if (intent.action == ACTION_SEND || intent.action == ACTION_SEND_MULTIPLE) {
        if (!isImportableType(intent.mimeType)) return null
        val content = SharedContent(
            uris = intent.streamUris.filter { it.isNotBlank() },
            mimeType = intent.mimeType,
            text = intent.text?.takeIf { it.isNotBlank() },
        )
        return if (content.isEmpty) null else LaunchRequest.ImportShared(content)
    }
    return null
}
