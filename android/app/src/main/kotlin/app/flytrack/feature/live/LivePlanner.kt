package app.flytrack.feature.live

import app.flytrack.core.model.TrackedFlightDetails
import app.flytrack.feature.widget.NextFlightWidgetModel
import app.flytrack.feature.widget.NextFlightWidgetModelBuilder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** What the live surfaces should look like at one moment, and when to look again. */
data class LivePlan(
    /** The live notification, or null to cancel it. */
    val notification: LiveNotificationContent?,
    /** The widget's model; the widget is redrawn when its redraw key changes. */
    val widget: NextFlightWidgetModel,
    /** The periodic WorkManager tick is needed (a flight is live or starts within [LivePlanner.TICK_HORIZON]). */
    val needsTick: Boolean,
    /** When the next live window opens, for an exact one-off wake-up; null when none is coming. */
    val nextWindowStart: Instant?,
) {
    val widgetRedrawKey: Any
        get() = when (widget) {
            NextFlightWidgetModel.Empty -> widget
            is NextFlightWidgetModel.Upcoming -> widget.redrawKey
        }
}

/** Pure decision for the live notification and the widget (no android.*). */
class LivePlanner(strings: LiveStrings) {
    private val formatter = LiveContentFormatter(strings)
    private val widgetBuilder = NextFlightWidgetModelBuilder(strings)

    /**
     * @param isEligible Pro on this flight (everyone on Pro, or the free trial flight)
     * @param dismissed flights whose live notification the user swiped away
     */
    fun plan(
        tracked: List<TrackedFlightDetails>,
        now: Instant,
        isEligible: (flightId: String) -> Boolean,
        dismissed: Set<String>,
    ): LivePlan {
        val live = LiveFlightSelector.selectLive(tracked, now, isEligible, dismissed)
        val notification = live?.let { formatter.notification(it, now) }
        val widget = widgetBuilder.build(tracked, now)
        val soon = tracked.any { details ->
            if (details.tracking.isArchived) return@any false
            val times = details.flight.liveTimes() ?: return@any false
            times.windowEnd > now && times.windowStart <= now + TICK_HORIZON
        }
        return LivePlan(
            notification = notification,
            widget = widget,
            needsTick = notification != null || soon,
            nextWindowStart = LiveFlightSelector.nextWindowStart(tracked, now, isEligible),
        )
    }

    companion object {
        /** The 15-minute tick runs while a flight's live window starts within this horizon. */
        val TICK_HORIZON: Duration = 24.hours
    }
}
