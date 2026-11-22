package app.flytrack.feature.flights.presentation

import app.flytrack.core.model.BestTime
import app.flytrack.core.model.DELAY_THRESHOLD_MINUTES
import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightPhase
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.FlightStatusTone
import app.flytrack.core.model.TimeKind
import app.flytrack.core.model.arrivalDelayMinutes
import app.flytrack.core.model.bestArrivalTime
import app.flytrack.core.model.bestDepartureTime
import app.flytrack.core.model.departureDelayMinutes
import app.flytrack.core.model.duration
import app.flytrack.core.model.hasDeparted
import app.flytrack.core.model.hasLanded
import app.flytrack.core.model.phase
import app.flytrack.core.model.progress
import app.flytrack.core.model.statusTone
import app.flytrack.core.time.FlightTimeFormat
import app.flytrack.core.time.relativeDay
import app.flytrack.core.time.relativeTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Domain flight -> what the card shows at [now].
 * Times are formatted in the departure/arrival AIRPORT's zone, never the phone's.
 * @param newFields fields whose value appeared or changed recently (see [FlightChangeTracker])
 */
fun Flight.toCardUiModel(now: Instant, newFields: Set<FlightField> = emptySet()): FlightCardUiModel {
    val departure = Punctuality.of(departureDelayMinutes)
    val arrival = Punctuality.of(arrivalDelayMinutes)
    val bestDep = bestDepartureTime
    val bestArr = bestArrivalTime
    val departureDate = bestDep?.let { FlightTimeFormat.localDate(it.instant, depTz) } ?: flightDate

    return FlightCardUiModel(
        flightId = id,
        designator = designator,
        airlineName = airlineName?.takeIf { it.isNotBlank() },
        depCode = depIata,
        arrCode = arrIata,
        depCity = depAirport?.displayCity ?: depIata,
        arrCity = arrAirport?.displayCity ?: arrIata,
        phase = phase,
        statusWord = statusWord(departure),
        tone = cardTone(),
        statusLine = statusLine(departure, arrival),
        departure = bestDep?.let { timeUiModel(it, schedDep, depTz, departure, dayOffset = 0) },
        arrival = bestArr?.let {
            val offset = bestDep?.let { dep -> FlightTimeFormat.arrivalDayOffset(dep.instant, depTz, it.instant, arrTz) } ?: 0
            timeUiModel(it, schedArr, arrTz, arrival, offset)
        },
        departureDate = departureDate,
        departureDay = relativeDay(departureDate, FlightTimeFormat.localDate(now, depTz)),
        duration = duration,
        info = cardInfo(newFields),
        countdown = countdown(now),
        progress = progress(now).takeIf { phase == FlightPhase.IN_AIR },
        updatedAt = listOfNotNull(lastPolledAt, updatedAt).maxOrNull(),
        asOf = now,
    )
}

private fun Flight.statusWord(departure: Punctuality): FlightStatusWord = when {
    status == FlightStatus.CANCELLED -> FlightStatusWord.CANCELLED
    status == FlightStatus.DIVERTED -> FlightStatusWord.DIVERTED
    hasLanded -> if (status == FlightStatus.ARRIVED) FlightStatusWord.ARRIVED else FlightStatusWord.LANDED
    hasDeparted -> when (status) {
        FlightStatus.APPROACHING -> FlightStatusWord.LANDING_SOON
        FlightStatus.EN_ROUTE -> FlightStatusWord.IN_THE_AIR
        else -> FlightStatusWord.DEPARTED
    }
    status == FlightStatus.BOARDING -> FlightStatusWord.BOARDING
    status == FlightStatus.GATE_CLOSED -> FlightStatusWord.GATE_CLOSED
    departure is Punctuality.Late -> FlightStatusWord.DELAYED
    departure is Punctuality.Early -> FlightStatusWord.EARLY
    status == FlightStatus.CHECK_IN -> FlightStatusWord.CHECK_IN_OPEN
    departure is Punctuality.OnTime -> FlightStatusWord.ON_TIME
    status == FlightStatus.UNKNOWN -> FlightStatusWord.SCHEDULE_ONLY
    else -> FlightStatusWord.SCHEDULED
}

/**
 * The model's tone, with two exceptions so the colour never contradicts the words next to it:
 * in the air the arrival estimate decides once one exists (a flight that left late but will land
 * on time is not "delayed"), and a flight with an estimate is not "schedule only" just because
 * the provider sent no status.
 */
private fun Flight.cardTone(): FlightStatusTone {
    val arrivalDelay = arrivalDelayMinutes
    val tone = statusTone
    return when {
        phase == FlightPhase.IN_AIR && arrivalDelay != null ->
            if (arrivalDelay >= DELAY_THRESHOLD_MINUTES) FlightStatusTone.DELAYED else FlightStatusTone.ACTIVE
        tone == FlightStatusTone.ESTIMATED && departureDelayMinutes != null -> FlightStatusTone.ON_TIME
        else -> tone
    }
}

private fun Flight.statusLine(departure: Punctuality, arrival: Punctuality): StatusLine = when (phase) {
    FlightPhase.CANCELLED -> StatusLine.Cancelled
    FlightPhase.DIVERTED -> StatusLine.Diverted(landed = hasLanded)
    FlightPhase.LANDED -> StatusLine.Landed(arrival, atGate = status == FlightStatus.ARRIVED)
    FlightPhase.IN_AIR -> StatusLine.InAir(arrival, departure)
    FlightPhase.BOARDING ->
        if (status == FlightStatus.GATE_CLOSED) {
            StatusLine.GateClosed(departure)
        } else {
            StatusLine.Boarding(depGate?.takeIf { it.isNotBlank() }, departure)
        }
    FlightPhase.CHECK_IN -> StatusLine.CheckInOpen(departure)
    FlightPhase.UPCOMING ->
        if (status == FlightStatus.UNKNOWN && departure == Punctuality.Unknown) {
            StatusLine.ScheduleOnly
        } else {
            StatusLine.BeforeDeparture(departure)
        }
}

private fun timeUiModel(
    best: BestTime,
    scheduled: Instant?,
    tz: String?,
    punctuality: Punctuality,
    dayOffset: Int,
): FlightTimeUiModel {
    val shifted = punctuality is Punctuality.Late || punctuality is Punctuality.Early
    return FlightTimeUiModel(
        time = FlightTimeFormat.time(best.instant, tz),
        kind = best.kind,
        replacedTime = scheduled?.takeIf { shifted && best.kind != TimeKind.SCHEDULED }?.let { FlightTimeFormat.time(it, tz) },
        date = FlightTimeFormat.localDate(best.instant, tz),
        dayOffset = dayOffset,
    )
}

/** Departure side until take-off, arrival side afterwards; nothing for a cancelled flight. */
private fun Flight.cardInfo(newFields: Set<FlightField>): List<FlightInfoUiModel> {
    val fields = when {
        status == FlightStatus.CANCELLED -> emptyList()
        hasDeparted -> listOf(FlightField.ARR_TERMINAL, FlightField.ARR_GATE, FlightField.BAGGAGE_BELT)
        else -> listOf(FlightField.DEP_TERMINAL, FlightField.DEP_GATE)
    }
    return fields.mapNotNull { field ->
        valueOf(field)?.let { FlightInfoUiModel(field, it, isNew = field in newFields) }
    }
}

/** The flight's value for [field], null when blank or not announced. */
fun Flight.valueOf(field: FlightField): String? = when (field) {
    FlightField.DEP_TERMINAL -> depTerminal
    FlightField.DEP_GATE -> depGate
    FlightField.CHECK_IN_DESK -> depCheckin
    FlightField.ARR_TERMINAL -> arrTerminal
    FlightField.ARR_GATE -> arrGate
    FlightField.BAGGAGE_BELT -> baggageBelt
}?.trim()?.takeIf { it.isNotEmpty() }

private fun Flight.countdown(now: Instant): Countdown? = when (phase) {
    FlightPhase.CANCELLED, FlightPhase.DIVERTED, FlightPhase.LANDED -> null
    FlightPhase.IN_AIR ->
        bestArrivalTime?.instant?.takeIf { it > now }?.let { Countdown.Lands(relativeTime(it, now)) }
    FlightPhase.UPCOMING, FlightPhase.CHECK_IN, FlightPhase.BOARDING -> {
        val dep = bestDepartureTime?.instant
        when {
            // Past its time without having left: the data is behind, so a countdown would mislead.
            dep == null || dep <= now -> null
            dep - now < 24.hours -> Countdown.Departs(relativeTime(dep, now))
            else -> Countdown.DepartsOn(relativeDay(dep, now, depTz))
        }
    }
}
