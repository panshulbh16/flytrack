package app.flytrack.feature.friends

import app.flytrack.core.push.PushContract

/**
 * Friend alerts (P2 wave 2, section 1): a push about a friend's flight, sent to friends who do not track it
 * themselves. Same `kind = "alert"` message as a tracker alert, plus these data keys (notify.ts).
 */
object FriendPushContract {
    /** `"friend"` for friend alerts; absent (tracker) otherwise. */
    const val KEY_ROLE = "role"
    const val ROLE_FRIEND = "friend"

    /** The friend's name as the alert text uses it ("Asha", or "Your friend" / "Aapka dost"). */
    const val KEY_FRIEND_NAME = "friend_name"

    /** The only event types that are critical for friends (`FRIEND_CRITICAL` in friend-alerts.ts). */
    val CRITICAL_EVENT_TYPES = setOf("CANCELLED", "REINSTATED", "DIVERTED", "DIVERTED_DEST")
}

/** True when the FCM data map is a friend alert. */
fun isFriendAlert(data: Map<String, String>): Boolean = data[FriendPushContract.KEY_ROLE]?.trim() == FriendPushContract.ROLE_FRIEND

/** The friend's name from a friend alert, or null. */
fun friendNameOf(data: Map<String, String>): String? = data[FriendPushContract.KEY_FRIEND_NAME]?.trim()?.ifEmpty { null }

/**
 * The channel the server names for a friend alert: critical only for cancellation and diversion, quiet for
 * the rest during quiet hours, updates otherwise. A friend's delay must not ring like one's own gate change.
 */
fun friendAlertChannelId(eventType: String, quiet: Boolean): String = when {
    eventType in FriendPushContract.CRITICAL_EVENT_TYPES -> PushContract.CHANNEL_CRITICAL
    quiet -> PushContract.CHANNEL_QUIET
    else -> PushContract.CHANNEL_UPDATES
}
