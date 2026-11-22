package app.flytrack.push

import app.flytrack.core.model.AlertType
import app.flytrack.core.push.PushContract

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
    eventType.startsWith("GATE") -> "gate"
    eventType.startsWith("BELT") -> "belt"
    eventType.startsWith("DELAY") || eventType == AlertType.INBOUND_LATE.wire ||
        eventType == AlertType.SCHEDULE_CHANGE.wire -> "delay"
    else -> eventType.lowercase()
}

/** Channel the server would have named for this event. Unknown types use the critical channel. */
fun alertChannelId(type: AlertType?): String =
    if (type == null || type.critical) PushContract.CHANNEL_CRITICAL else PushContract.CHANNEL_UPDATES

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
    return IncomingPush.Alert(
        flightId = flightId,
        eventId = data[PushContract.KEY_EVENT_ID]?.takeIf { it.isNotBlank() },
        eventType = type,
        title = title,
        body = body,
        channelId = alertChannelId(type),
        tag = tag,
        notificationId = notificationIdFor(tag),
    )
}
