package app.flytrack.feature.widget

import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightStatusTone
import app.flytrack.core.model.TrackedFlightDetails
import app.flytrack.core.model.bestArrival
import app.flytrack.core.model.bestDeparture
import app.flytrack.core.model.hasDeparted
import app.flytrack.core.model.hasLanded
import app.flytrack.core.model.statusTone
import app.flytrack.core.time.FlightTimeFormat
import app.flytrack.core.time.RelativeDay
import app.flytrack.core.time.relativeDay
import app.flytrack.feature.live.LiveContentFormatter
import app.flytrack.feature.live.LiveFlightSelector
import app.flytrack.feature.live.LiveStatusWord
import app.flytrack.feature.live.LiveStrings
import app.flytrack.feature.live.liveProgress
import java.util.Locale
import kotlin.time.Instant

/** What the Next flight widget draws. Pure values, built by [NextFlightWidgetModelBuilder]. */
sealed interface NextFlightWidgetModel {
    /** No active flight to show: the widget invites the user to add one. */
    data object Empty : NextFlightWidgetModel

    data class Upcoming(
        val flightId: String,
        /** "DEL → BOM". */
        val route: String,
        /** "6E 2341". */
        val designator: String,
        val statusWord: LiveStatusWord,
        /** "On time", "Delayed 25 min", "Boarding". */
        val statusText: String,
        val tone: FlightStatusTone,
        /** "Departs 18:25", "Arrives 20:40", "Landed 20:38". */
        val timeText: String?,
        /** "Wed, 25 Nov" when the departure is not today at the airport, else null. */
        val dateText: String?,
        /** "Gate 42B · T1" before departure, "Belt 5" after; null when nothing is known. */
        val gateBeltText: String?,
        /** "Departs in 2h 05m", "Lands in 35 min"; null once landed or cancelled. */
        val countdownText: String?,
        /** True for the offline estimate. */
        val estimated: Boolean,
    ) : NextFlightWidgetModel {
        /** The widget is redrawn only when this changes (or on the schedule, for the countdown). */
        val redrawKey: Upcoming get() = copy(countdownText = null)
    }
}

/** Turns Room's tracked flights into the widget model. */
class NextFlightWidgetModelBuilder(
    private val strings: LiveStrings,
    private val locale: Locale = Locale.getDefault(),
) {
    private val formatter = LiveContentFormatter(strings)

    fun build(tracked: List<TrackedFlightDetails>, now: Instant): NextFlightWidgetModel {
        val flight = LiveFlightSelector.selectWidget(tracked, now) ?: return NextFlightWidgetModel.Empty
        return build(flight, now)
    }

    fun build(flight: Flight, now: Instant): NextFlightWidgetModel.Upcoming {
        val progress = liveProgress(flight, now)
        val word = LiveContentFormatter.statusWord(flight, progress)
        val cancelled = word == LiveStatusWord.CANCELLED
        val landed = word == LiveStatusWord.LANDED || flight.hasLanded
        val inAir = !landed && (flight.hasDeparted || word == LiveStatusWord.IN_AIR)

        val timeText = when {
            landed -> formatter.arrivalTime(flight)?.let(strings::landedAt)
            inAir -> formatter.arrivalTime(flight)?.let(strings::arrives)
            else -> formatter.departureTime(flight)?.let(strings::departs)
        }
        val departure = flight.bestDeparture
        val dateText = departure
            ?.takeIf { !inAir && !landed && relativeDay(it, now, flight.depTz) != RelativeDay.Today }
            ?.let { FlightTimeFormat.weekdayDayMonth(it, flight.depTz, locale) }
        val gateBelt = if (inAir || landed) {
            listOfNotNull(flight.baggageBelt?.let(strings::belt))
        } else {
            listOfNotNull(flight.depGate?.let(strings::gate), flight.depTerminal?.let(strings::terminal))
        }
        val countdown = when {
            cancelled || landed -> null
            inAir -> flight.bestArrival?.let { formatter.countdown(it - now) }?.let(strings::landsIn)
            else -> departure?.let { formatter.countdown(it - now) }?.let(strings::departsIn)
        }
        val estimated = progress?.estimated == true
        return NextFlightWidgetModel.Upcoming(
            flightId = flight.id,
            route = formatter.route(flight),
            designator = flight.designator,
            statusWord = word,
            statusText = formatter.statusText(flight, word),
            tone = if (estimated) FlightStatusTone.ESTIMATED else flight.statusTone,
            timeText = timeText,
            dateText = dateText,
            gateBeltText = gateBelt.takeIf { it.isNotEmpty() }?.let(strings::join),
            countdownText = countdown,
            estimated = estimated,
        )
    }
}
