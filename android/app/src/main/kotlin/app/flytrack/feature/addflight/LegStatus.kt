package app.flytrack.feature.addflight

import app.flytrack.core.model.DELAY_THRESHOLD_MINUTES
import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightPhase
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.departureDelayMinutes
import app.flytrack.core.model.phase
import kotlin.time.Duration
import kotlin.time.Instant

/** The plain-language status of a search result, before it becomes text (see `legStatusText`). */
sealed interface LegStatus {
    /** In the timetable, no live estimate yet. */
    data object Scheduled : LegStatus

    data object OnTime : LegStatus

    /** Departure is [minutes] behind schedule (15 or more). */
    data class Late(val minutes: Int) : LegStatus

    data object Boarding : LegStatus

    data object InAir : LegStatus

    data object Landed : LegStatus

    data object Cancelled : LegStatus

    data object Diverted : LegStatus

    /** The provider sent no status at all: the times are the timetable only. */
    data object ScheduleOnly : LegStatus
}

val Flight.legStatus: LegStatus
    get() = when (phase) {
        FlightPhase.CANCELLED -> LegStatus.Cancelled
        FlightPhase.DIVERTED -> LegStatus.Diverted
        FlightPhase.LANDED -> LegStatus.Landed
        FlightPhase.IN_AIR -> LegStatus.InAir
        FlightPhase.BOARDING, FlightPhase.CHECK_IN, FlightPhase.UPCOMING -> {
            val delay = departureDelayMinutes
            when {
                delay != null && delay >= DELAY_THRESHOLD_MINUTES -> LegStatus.Late(delay)
                phase == FlightPhase.BOARDING -> LegStatus.Boarding
                status == FlightStatus.UNKNOWN -> LegStatus.ScheduleOnly
                // "On time" is a claim: make it only when the airline gave an estimate or opened check-in.
                delay != null || phase == FlightPhase.CHECK_IN -> LegStatus.OnTime
                else -> LegStatus.Scheduled
            }
        }
    }

/** The timetable departure, or the best newer time when the provider sent no schedule. */
val Flight.listedDeparture: Instant? get() = schedDep ?: estDep ?: actDep

val Flight.listedArrival: Instant? get() = schedArr ?: estArr ?: actArr

/** Gate-to-gate time of the times shown in the list; null when unknown or inconsistent. */
val Flight.listedDuration: Duration?
    get() {
        val dep = listedDeparture ?: return null
        val arr = listedArrival ?: return null
        return (arr - dep).takeIf { it.isPositive() }
    }
