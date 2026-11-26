package app.flytrack.push

import app.flytrack.core.model.AlertType
import app.flytrack.core.push.PushContract
import app.flytrack.feature.checkin.CHECKIN_EVENT_OPEN
import app.flytrack.feature.checkin.KEY_CHECKIN_URL
import app.flytrack.feature.checkin.checkinUrlFromPush
import app.flytrack.feature.friends.friendAlertChannelId
import app.flytrack.feature.friends.friendNameOf
import app.flytrack.feature.friends.isFriendAlert

/**
 * One FCM data payload, parsed without any Android type so it can be unit-tested against the
 * messages `notify.ts` actually sends.
 */
sealed interface IncomingPush {
    /** Data-only sync. [flightJson] is the compact flight, or null when the message omitted it. */
    data class Sync(val flightId: String, val flightJson: String?) : IncomingPush

    /**
     * An alert the app must show itself while it is in the foreground. [channelId] and [tag] are
     * the same values the server puts on the notification, so the shade looks identical.
     */
    data class Alert(
        val flightId: String,
        val eventId: String?,
        val eventType: AlertType?,
        val title: String,
        val body: String,
        val channelId: String,
        val tag: String,
        val notificationId: Int,
        /** CHECKIN_OPEN / CHECKIN_CLOSING only: the airline's https check-in page a tap opens. */
        val checkinUrl: String? = null,
        /** The server sent this info alert silently (the user's quiet hours): it goes on [PushContract.CHANNEL_QUIET]. */
        val quiet: Boolean = false,
        /** P2 wave 2: an alert about a friend's flight (`role = "friend"`); its text already names the friend. */
        val isFriend: Boolean = false,
        /** Friend alerts only: the friend's name (`friend_name`). */
        val friendName: String? = null,
    ) : IncomingPush

    /** Not a message this app understands (missing kind, missing flight, empty alert). */
    data object Ignored : IncomingPush
}

/**
 * Turns the string data map of a RemoteMessage into a typed message.
 * Unknown alert types still produce an alert, on the critical channel: dropping a gate change
 * the app does not recognise yet would be worse than showing it loudly.
 */
fun parseIncomingPush(data: Map<String, String>): IncomingPush {
    val flightId = data[PushContract.KEY_FLIGHT_ID]?.trim().orEmpty()
    if (flightId.isEmpty()) return IncomingPush.Ignored
    return when (data[PushContract.KEY_KIND]) {
        PushContract.KIND_SYNC -> IncomingPush.Sync(
            flightId = flightId,
            flightJson = data[PushContract.KEY_FLIGHT]?.takeIf { it.isNotBlank() },
        )
        PushContract.KIND_ALERT -> alertOrIgnored(data, flightId)
        else -> IncomingPush.Ignored
    }
}

/** Tag group from `tagGroup` in notify.ts. A newer alert with the same tag replaces the old one. */
fun alertTagGroup(type: AlertType): String = alertTagGroup(type.wire)

/** Same rules as [alertTagGroup] for a wire value this app may not know yet. */
fun alertTagGroup(eventType: String): String = when {
    eventType == AlertType.TERMINAL_CHANGE.wire -> "terminal"
    // P3: "gate closed" replaces "Boarding now" (never a gate change), and the runway time refines "has departed".
    eventType == AlertType.GATE_CLOSED.wire -> "boarding"
    eventType == AlertType.TAKEOFF.wire -> "departed"
    eventType == AlertType.CONNECTION_AT_RISK.wire -> "connection"
    eventType.startsWith("GATE") -> "gate"
    eventType.startsWith("BELT") -> "belt"
    eventType.startsWith("DELAY") || eventType == AlertType.INBOUND_LATE.wire ||
        eventType == AlertType.SCHEDULE_CHANGE.wire -> "delay"
    // Replaces the "diverted, new destination not announced yet" alert.
    eventType == AlertType.DIVERTED_DEST.wire -> "diverted"
    else -> eventType.lowercase()
}

/** Channel the server would have named for this event. Unknown types use the critical channel. */
fun alertChannelId(type: AlertType?): String =
    if (type == null || type.critical) PushContract.CHANNEL_CRITICAL else PushContract.CHANNEL_UPDATES

/**
 * [alertChannelId] for a wire value [AlertType] may not know yet. CHECKIN_OPEN is an `info` event
 * (P1 contract, section 3), so the server puts it on the updates channel; the app must not post the
 * same alert loudly. CHECKIN_CLOSING is critical, which the unknown-type default already gives.
 */
fun alertChannelId(type: AlertType?, wire: String): String =
    if (type == null && wire == CHECKIN_EVENT_OPEN) PushContract.CHANNEL_UPDATES else alertChannelId(type)

/**
 * [alertChannelId] for an alert the server may have sent silently (`quiet = "1"`, P2 contract section 3).
 * Critical alerts are always loud, so a quiet flag on a critical type is ignored. The server only marks
 * info alerts quiet, so an unknown type that carries the flag goes on the quiet channel too.
 */
fun alertChannelId(type: AlertType?, wire: String, quiet: Boolean): String = when {
    !quiet -> alertChannelId(type, wire)
    type?.critical == true -> PushContract.CHANNEL_CRITICAL
    else -> PushContract.CHANNEL_QUIET
}

/**
 * Stable notification id for a tag. Android replaces a notification only when both the tag and
 * the id match, so the id is a pure function of the tag.
 */
fun notificationIdFor(tag: String): Int {
    var hash = 0x811c9dc5.toInt()
    for (ch in tag) {
        hash = hash xor ch.code
        hash *= 0x01000193
    }
    val id = hash and 0x7fffffff
    return if (id == 0) 1 else id
}

private fun alertOrIgnored(data: Map<String, String>, flightId: String): IncomingPush {
    val title = data[PushContract.KEY_TITLE]?.trim().orEmpty()
    val body = data[PushContract.KEY_BODY]?.trim().orEmpty()
    if (title.isEmpty() && body.isEmpty()) return IncomingPush.Ignored
    val wire = data[PushContract.KEY_EVENT_TYPE]?.trim().orEmpty()
    val type = AlertType.fromWire(wire)
    val group = if (wire.isEmpty()) "alert" else alertTagGroup(wire)
    val tag = PushContract.notificationTag(flightId, group)
    val quiet = data[PushContract.KEY_QUIET]?.trim() == PushContract.QUIET_TRUE
    val friend = isFriendAlert(data)
    // Friend alerts: only cancellation and diversion are critical (friend-alerts.ts), so a friend's delay is not loud.
    val channelId = if (friend) friendAlertChannelId(wire, quiet) else alertChannelId(type, wire, quiet)
    return IncomingPush.Alert(
        flightId = flightId,
        eventId = data[PushContract.KEY_EVENT_ID]?.takeIf { it.isNotBlank() },
        eventType = type,
        title = title,
        body = body,
        channelId = channelId,
        tag = tag,
        notificationId = notificationIdFor(tag),
        checkinUrl = checkinUrlFromPush(wire, data[KEY_CHECKIN_URL]),
        quiet = channelId == PushContract.CHANNEL_QUIET,
        isFriend = friend,
        friendName = if (friend) friendNameOf(data) else null,
    )
}
