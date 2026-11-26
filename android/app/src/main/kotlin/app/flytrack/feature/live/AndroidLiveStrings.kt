package app.flytrack.feature.live

import android.content.Context
import app.flytrack.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** [LiveStrings] from `strings_live.xml`. Reads the resources on every call, so a language change applies at once. */
@Singleton
class AndroidLiveStrings @Inject constructor(
    @ApplicationContext private val context: Context,
) : LiveStrings {

    override fun statusWord(word: LiveStatusWord): String = context.getString(
        when (word) {
            LiveStatusWord.ON_TIME -> R.string.live_status_on_time
            LiveStatusWord.DELAYED -> R.string.live_status_delayed
            LiveStatusWord.CHECK_IN -> R.string.live_status_check_in
            LiveStatusWord.BOARDING -> R.string.live_status_boarding
            LiveStatusWord.GATE_CLOSED -> R.string.live_status_gate_closed
            LiveStatusWord.DEPARTED -> R.string.live_status_departed
            LiveStatusWord.IN_AIR -> R.string.live_status_in_air
            LiveStatusWord.LANDED -> R.string.live_status_landed
            LiveStatusWord.CANCELLED -> R.string.live_status_cancelled
            LiveStatusWord.DIVERTED -> R.string.live_status_diverted
        },
    )

    override fun delayedBy(minutes: Int): String = context.getString(R.string.live_status_delayed_by, minutes)

    override fun route(dep: String, arr: String): String = context.getString(R.string.live_route, dep, arr)

    override fun title(designator: String, route: String): String = context.getString(R.string.live_title, designator, route)

    override fun departs(time: String): String = context.getString(R.string.live_departs, time)

    override fun arrives(time: String): String = context.getString(R.string.live_arrives, time)

    override fun landedAt(time: String): String = context.getString(R.string.live_landed_at, time)

    override fun gate(gate: String): String = context.getString(R.string.live_gate, gate)

    override fun terminal(terminal: String): String = context.getString(R.string.live_terminal, terminal)

    override fun belt(belt: String): String = context.getString(R.string.live_belt, belt)

    override fun estimated(): String = context.getString(R.string.live_estimated)

    override fun join(parts: List<String>): String = parts.joinToString(context.getString(R.string.live_separator))

    override fun countdownDays(days: Int): String = context.resources.getQuantityString(R.plurals.live_countdown_days, days, days)

    override fun countdownHoursMinutes(hours: Int, minutes: Int): String =
        context.getString(R.string.live_countdown_hours_minutes, hours, minutes)

    override fun countdownMinutes(minutes: Int): String = context.getString(R.string.live_countdown_minutes, minutes)

    override fun departsIn(span: String): String = context.getString(R.string.live_departs_in, span)

    override fun landsIn(span: String): String = context.getString(R.string.live_lands_in, span)
}
