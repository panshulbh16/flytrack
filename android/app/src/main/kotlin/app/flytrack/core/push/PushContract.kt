package app.flytrack.core.push

/**
 * The FCM contract with the backend (supabase/functions/_shared/notify.ts). Every value here must
 * stay identical to the server's, otherwise alerts land on a missing channel or taps open nothing.
 */
object PushContract {
    /** Gate/belt/delay/cancel/boarding alerts: heads-up with sound. */
    const val CHANNEL_CRITICAL = "flight_critical"

    /** Departed/landed/gate assigned: normal importance. */
    const val CHANNEL_UPDATES = "flight_updates"

    /** `click_action` of alert notifications = the intent action MainActivity listens for. */
    const val ACTION_OPEN_FLIGHT = "OPEN_FLIGHT"

    /** FCM data keys. When a notification is tapped they arrive as String intent extras with the same names. */
    const val KEY_KIND = "kind"
    const val KEY_FLIGHT_ID = "flight_id"
    const val KEY_EVENT_ID = "event_id"
    const val KEY_EVENT_TYPE = "event_type"
    const val KEY_TITLE = "title"
    const val KEY_BODY = "body"

    /** Sync messages only: the compact flight JSON (see CompactFlightDto). */
    const val KEY_FLIGHT = "flight"

    /** Values of [KEY_KIND]. */
    const val KIND_ALERT = "alert"
    const val KIND_SYNC = "sync"

    /** Notification tag used by the server: "<flightId>:<group>", so a newer alert replaces the older one. */
    fun notificationTag(flightId: String, group: String): String = "$flightId:$group"
}
