package app.flytrack.wear.ui

import android.content.Context
import app.flytrack.wear.R

/** [WearStrings] from `res/values/strings.xml`. */
class ResourceWearStrings(context: Context) : WearStrings {
    private val res = context.applicationContext.resources

    override fun status(status: WatchStatus): String = res.getString(
        when (status) {
            WatchStatus.ON_TIME -> R.string.wear_status_on_time
            WatchStatus.DELAYED -> R.string.wear_status_delayed
            WatchStatus.CHECK_IN -> R.string.wear_status_check_in
            WatchStatus.BOARDING -> R.string.wear_status_boarding
            WatchStatus.GATE_CLOSED -> R.string.wear_status_gate_closed
            WatchStatus.DEPARTED -> R.string.wear_status_departed
            WatchStatus.IN_AIR -> R.string.wear_status_in_air
            WatchStatus.LANDED -> R.string.wear_status_landed
            WatchStatus.CANCELLED -> R.string.wear_status_cancelled
            WatchStatus.DIVERTED -> R.string.wear_status_diverted
        },
    )

    override fun delayedBy(minutes: Int): String = res.getString(R.string.wear_status_delayed_by, minutes)

    override fun route(dep: String, arr: String): String = res.getString(R.string.wear_route, dep, arr)

    override fun gate(gate: String): String = res.getString(R.string.wear_gate, gate)

    override fun terminal(terminal: String): String = res.getString(R.string.wear_terminal, terminal)

    override fun belt(belt: String): String = res.getString(R.string.wear_belt, belt)

    override fun departs(time: String): String = res.getString(R.string.wear_departs, time)

    override fun join(parts: List<String>): String = parts.joinToString(res.getString(R.string.wear_separator))
}
