package app.flytrack.feature.flights.presentation

import app.flytrack.core.model.Cabin
import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.InboundPrediction
import app.flytrack.core.model.TrackedFlight
import app.flytrack.core.model.distanceKm
import app.flytrack.core.model.hasDeparted
import app.flytrack.core.time.FlightTimeFormat
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/** Whether, and how, the user follows the flight. */
enum class TrackingState { TRACKED, ARCHIVED, NOT_TRACKED }

/** One end of the journey on the detail screen. All times are in this airport's zone. */
data class EndpointUiModel(
    val code: String,
    val city: String,
    val airportName: String?,
    /** The headline time; null when not even a timetable time is known. */
    val time: FlightTimeUiModel?,
    val scheduled: String?,
    val estimated: String?,
    val actual: String?,
    /**
     * Terminal, gate and check-in desk (departure) or terminal, gate and belt (arrival).
     * A null value means "not announced"; such rows are left out once the value can no longer matter.
     */
    val info: List<FlightInfoUiModel>,
)

/** "Where's my plane": the aircraft's previous leg (Pro). */
sealed interface ProInsightUiModel {
    /** Nothing to show and nothing to tease. */
    data object None : ProInsightUiModel

    /** A Pro prediction exists (`has_pro_insight`) but this user may not see it. Carries no Pro data on purpose. */
    data object Teaser : ProInsightUiModel

    /**
     * @property inboundFlight "6E 2032"
     * @property inboundFrom IATA code the aircraft is coming from
     * @property inboundArrival when the aircraft reaches (or reached) this flight's departure airport, in that airport's zone
     * @property inboundLanded true once the aircraft is on the ground here
     * @property lateMinutes minutes later than the airline says; below [LATE_FROM_MINUTES] counts as on time
     * @property predictedDeparture "19:10" in the departure airport's zone
     * @property reason the server's own sentence (English), null when the aircraft is on time
     */
    data class Details(
        val inboundFlight: String?,
        val inboundFrom: String?,
        val inboundArrival: String?,
        val inboundLanded: Boolean,
        val lateMinutes: Int,
        val predictedDeparture: String?,
        val reason: String?,
    ) : ProInsightUiModel {
        val isLate: Boolean get() = lateMinutes >= LATE_FROM_MINUTES
    }

    companion object {
        /** Same threshold as `has_pro_insight` in `flight_view`. */
        const val LATE_FROM_MINUTES = 10
    }
}

/** What the user attached to the tracked flight. */
data class BookingUiModel(
    val pnr: String?,
    val seat: String?,
    val cabin: Cabin?,
    val note: String?,
) {
    val isEmpty: Boolean get() = pnr == null && seat == null && cabin == null && note == null
}

/** Everything the detail screen shows. Built by [toDetailUiModel]. */
data class FlightDetailUiModel(
    val card: FlightCardUiModel,
    /** The timetable date of the flight at the departure airport. */
    val flightDate: LocalDate,
    val delayReason: String?,
    val departure: EndpointUiModel,
    val arrival: EndpointUiModel,
    val distanceKm: Int?,
    val aircraftModel: String?,
    val aircraftReg: String?,
    val proInsight: ProInsightUiModel,
    val trackingState: TrackingState,
    /** null when the flight is not tracked. */
    val booking: BookingUiModel?,
    /** Empty when the flight is not tracked. */
    val alerts: List<AlertToggleUiModel>,
)

/**
 * @param tracking the user's `user_flights` row, null when the flight is only cached
 * @param isPro whether the user may see Pro data. The server already masks it for free users; this
 *   second check keeps a row cached before a downgrade from leaking it.
 * @param newFields fields to emphasise (see [FlightChangeTracker])
 */
fun Flight.toDetailUiModel(
    tracking: TrackedFlight?,
    isPro: Boolean,
    now: Instant,
    newFields: Set<FlightField> = emptySet(),
): FlightDetailUiModel {
    val card = toCardUiModel(now, newFields)
    val cancelled = status == FlightStatus.CANCELLED

    // "Not announced" is only worth a row while the value can still turn up.
    fun info(stillExpected: Boolean, vararg fields: FlightField) = fields
        .map { FlightInfoUiModel(it, valueOf(it), isNew = it in newFields) }
        .filter { it.value != null || stillExpected }

    return FlightDetailUiModel(
        card = card,
        flightDate = flightDate,
        delayReason = delayReason?.trim()?.takeIf { it.isNotEmpty() },
        departure = EndpointUiModel(
            code = depIata,
            city = card.depCity,
            airportName = depAirport?.name,
            time = card.departure,
            scheduled = schedDep?.let { FlightTimeFormat.time(it, depTz) },
            estimated = estDep?.let { FlightTimeFormat.time(it, depTz) },
            actual = actDep?.let { FlightTimeFormat.time(it, depTz) },
            info = info(!cancelled && !hasDeparted, FlightField.DEP_TERMINAL, FlightField.DEP_GATE, FlightField.CHECK_IN_DESK),
        ),
        arrival = EndpointUiModel(
            code = arrIata,
            city = card.arrCity,
            airportName = arrAirport?.name,
            time = card.arrival,
            scheduled = schedArr?.let { FlightTimeFormat.time(it, arrTz) },
            estimated = estArr?.let { FlightTimeFormat.time(it, arrTz) },
            actual = actArr?.let { FlightTimeFormat.time(it, arrTz) },
            info = info(!cancelled, FlightField.ARR_TERMINAL, FlightField.ARR_GATE, FlightField.BAGGAGE_BELT),
        ),
        distanceKm = distanceKm,
        aircraftModel = aircraftModel?.trim()?.takeIf { it.isNotEmpty() },
        aircraftReg = aircraftReg?.trim()?.takeIf { it.isNotEmpty() },
        proInsight = proInsight(isPro),
        trackingState = when {
            tracking == null -> TrackingState.NOT_TRACKED
            tracking.isArchived -> TrackingState.ARCHIVED
            else -> TrackingState.TRACKED
        },
        booking = tracking?.let { BookingUiModel(it.pnr, it.seat, it.cabin, it.note?.takeIf { note -> note.isNotBlank() }) },
        alerts = tracking?.let { row ->
            AlertGroup.entries.map { group ->
                val locked = group.proOnly && !isPro
                AlertToggleUiModel(group, enabled = !locked && group.isEnabled(row), locked = locked)
            }
        }.orEmpty(),
    )
}

/**
 * Pro users get the prediction, everyone else at most the teaser that `has_pro_insight` asks for.
 * Once the flight has left (or is cancelled) the aircraft's previous leg no longer matters.
 */
private fun Flight.proInsight(isPro: Boolean): ProInsightUiModel {
    if (hasDeparted || status == FlightStatus.CANCELLED) return ProInsightUiModel.None
    val prediction = prediction
    return when {
        // A Pro user is never teased: without a prediction there is simply nothing to show yet.
        isPro -> prediction?.toDetails(depTz) ?: ProInsightUiModel.None
        hasProInsight -> ProInsightUiModel.Teaser
        else -> ProInsightUiModel.None
    }
}

private fun InboundPrediction.toDetails(depTz: String?): ProInsightUiModel.Details {
    val arrival = inbound?.let { it.actArr ?: it.estArr ?: it.schedArr }
    return ProInsightUiModel.Details(
        inboundFlight = inbound?.flight?.takeIf { it.isNotBlank() },
        inboundFrom = inbound?.fromIata?.takeIf { it.isNotBlank() },
        inboundArrival = arrival?.let { FlightTimeFormat.time(it, depTz) },
        inboundLanded = inbound?.let {
            it.actArr != null || it.status == FlightStatus.LANDED || it.status == FlightStatus.ARRIVED
        } ?: false,
        lateMinutes = lateMin.coerceAtLeast(0),
        predictedDeparture = predictedDep?.let { FlightTimeFormat.time(it, depTz) },
        reason = reason?.trim()?.takeIf { it.isNotEmpty() },
    )
}
