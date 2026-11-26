package app.flytrack.feature.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.flytrack.core.push.PushContract
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Flights whose live notification the user swiped away. They are not shown again (the alerts on
 * `flight_critical` still arrive). Device-local; keeps the most recent [MAX_REMEMBERED] ids.
 */
@Singleton
class LiveDismissals @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _dismissed = MutableStateFlow(read())
    val dismissed: StateFlow<Set<String>> = _dismissed.asStateFlow()

    fun dismiss(flightId: String) {
        val updated = rememberDismissal(readOrdered(), flightId)
        prefs.edit().putString(KEY_IDS, updated.joinToString(SEPARATOR)).apply()
        _dismissed.value = updated.toSet()
    }

    private fun read(): Set<String> = readOrdered().toSet()

    private fun readOrdered(): List<String> =
        prefs.getString(KEY_IDS, null)?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()

    private companion object {
        const val PREFS = "live_notification"
        const val KEY_IDS = "dismissed_flight_ids"
        const val SEPARATOR = ","
    }
}

/** Most recent last; at most [MAX_REMEMBERED]. Pure, for tests. */
internal fun rememberDismissal(current: List<String>, flightId: String): List<String> =
    (current.filter { it != flightId } + flightId).takeLast(MAX_REMEMBERED)

internal const val MAX_REMEMBERED = 20

/** Receives the live notification's delete intent (the user swiped it away). Not exported. */
@AndroidEntryPoint
class LiveDismissReceiver : BroadcastReceiver() {

    @Inject lateinit var dismissals: LiveDismissals

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISMISSED) return
        val flightId = intent.getStringExtra(PushContract.KEY_FLIGHT_ID) ?: return
        dismissals.dismiss(flightId)
    }

    companion object {
        const val ACTION_DISMISSED = "app.flytrack.live.DISMISSED"
    }
}
