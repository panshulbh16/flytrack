package app.flytrack.feature.flights.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.flytrack.R
import app.flytrack.core.model.Cabin
import app.flytrack.core.model.TimeKind
import app.flytrack.core.time.FlightTimeFormat
import app.flytrack.core.time.RelativeDay
import app.flytrack.feature.flights.presentation.AlertGroup
import app.flytrack.feature.flights.presentation.Countdown
import app.flytrack.feature.flights.presentation.FlightField
import app.flytrack.feature.flights.presentation.Punctuality
import app.flytrack.feature.flights.presentation.StatusLine
import app.flytrack.feature.flights.presentation.FlightStatusWord
import app.flytrack.ui.components.relativeDayText
import app.flytrack.ui.components.relativeTimeText
import kotlinx.datetime.LocalDate

// Turns the typed presentation values of feature.flights.presentation into localised text.

@Composable
@ReadOnlyComposable
fun statusWordText(word: FlightStatusWord): String = stringResource(
    when (word) {
        FlightStatusWord.SCHEDULE_ONLY -> R.string.flights_word_schedule_only
        FlightStatusWord.SCHEDULED -> R.string.flights_word_scheduled
        FlightStatusWord.ON_TIME -> R.string.flights_word_on_time
        FlightStatusWord.DELAYED -> R.string.flights_word_delayed
        FlightStatusWord.EARLY -> R.string.flights_word_early
        FlightStatusWord.CHECK_IN_OPEN -> R.string.flights_word_check_in_open
        FlightStatusWord.BOARDING -> R.string.flights_word_boarding
        FlightStatusWord.GATE_CLOSED -> R.string.flights_word_gate_closed
        FlightStatusWord.DEPARTED -> R.string.flights_word_departed
        FlightStatusWord.IN_THE_AIR -> R.string.flights_word_in_the_air
        FlightStatusWord.LANDING_SOON -> R.string.flights_word_landing_soon
        FlightStatusWord.LANDED -> R.string.flights_word_landed
        FlightStatusWord.ARRIVED -> R.string.flights_word_arrived
        FlightStatusWord.CANCELLED -> R.string.flights_word_cancelled
        FlightStatusWord.DIVERTED -> R.string.flights_word_diverted
    },
)

/** "25 min", "2 hr", "1 hr 5 min". */
@Composable
@ReadOnlyComposable
fun minutesAmountText(totalMinutes: Int): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val minutesText = pluralStringResource(R.plurals.flights_amount_minutes, minutes, minutes)
    if (hours == 0) return minutesText
    val hoursText = pluralStringResource(R.plurals.flights_amount_hours, hours, hours)
    return if (minutes == 0) hoursText else stringResource(R.string.flights_amount_hours_minutes, hoursText, minutesText)
}

/** The sentence under the status word. [arrCity] is used for "On the way to Mumbai". */
@Composable
@ReadOnlyComposable
fun statusLineText(line: StatusLine, arrCity: String): String = when (line) {
    StatusLine.ScheduleOnly -> stringResource(R.string.flights_line_schedule_only)
    is StatusLine.BeforeDeparture -> when (val p = line.departure) {
        is Punctuality.Late -> stringResource(R.string.flights_line_departure_late, minutesAmountText(p.minutes))
        is Punctuality.Early -> stringResource(R.string.flights_line_departure_early, minutesAmountText(p.minutes))
        Punctuality.OnTime -> stringResource(R.string.flights_line_departure_on_time)
        Punctuality.Unknown -> stringResource(R.string.flights_line_no_changes)
    }
    is StatusLine.CheckInOpen -> withDepartureClause(stringResource(R.string.flights_line_check_in_open), line.departure)
    is StatusLine.Boarding -> withDepartureClause(
        line.gate?.let { stringResource(R.string.flights_line_boarding_at_gate, it) } ?: stringResource(R.string.flights_line_boarding),
        line.departure,
    )
    is StatusLine.GateClosed -> withDepartureClause(stringResource(R.string.flights_line_gate_closed), line.departure)
    is StatusLine.InAir -> when (val arrival = line.arrival) {
        is Punctuality.Late -> stringResource(R.string.flights_line_arriving_late, minutesAmountText(arrival.minutes))
        is Punctuality.Early -> stringResource(R.string.flights_line_arriving_early, minutesAmountText(arrival.minutes))
        Punctuality.OnTime -> stringResource(R.string.flights_line_arriving_on_time)
        Punctuality.Unknown -> when (val departure = line.departure) {
            is Punctuality.Late -> stringResource(R.string.flights_line_took_off_late, minutesAmountText(departure.minutes))
            else -> stringResource(R.string.flights_line_on_the_way, arrCity)
        }
    }
    is StatusLine.Landed -> when (val p = line.arrival) {
        is Punctuality.Late -> stringResource(
            if (line.atGate) R.string.flights_line_arrived_late else R.string.flights_line_landed_late,
            minutesAmountText(p.minutes),
        )
        is Punctuality.Early -> stringResource(
            if (line.atGate) R.string.flights_line_arrived_early else R.string.flights_line_landed_early,
            minutesAmountText(p.minutes),
        )
        Punctuality.OnTime -> stringResource(if (line.atGate) R.string.flights_line_arrived_on_time else R.string.flights_line_landed_on_time)
        Punctuality.Unknown -> stringResource(if (line.atGate) R.string.flights_line_arrived else R.string.flights_line_landed)
    }
    StatusLine.Cancelled -> stringResource(R.string.flights_line_cancelled)
    is StatusLine.Diverted -> stringResource(if (line.landed) R.string.flights_line_diverted_landed else R.string.flights_line_diverted)
}

/** "Check-in is open · running 25 min late": only a real shift is worth the extra words. */
@Composable
@ReadOnlyComposable
private fun withDepartureClause(sentence: String, departure: Punctuality): String = when (departure) {
    is Punctuality.Late ->
        stringResource(R.string.flights_joined, sentence, stringResource(R.string.flights_clause_late, minutesAmountText(departure.minutes)))
    is Punctuality.Early ->
        stringResource(R.string.flights_joined, sentence, stringResource(R.string.flights_clause_early, minutesAmountText(departure.minutes)))
    Punctuality.OnTime, Punctuality.Unknown -> sentence
}

/** "in 35 min", "Tomorrow", "in 3 days": the bare distance, for the caption of the next-flight card. */
@Composable
@ReadOnlyComposable
fun countdownDistanceText(countdown: Countdown): String = when (countdown) {
    is Countdown.Departs -> relativeTimeText(countdown.at)
    is Countdown.DepartsOn -> relativeDayText(countdown.day)
    is Countdown.Lands -> stringResource(R.string.flights_countdown_lands, relativeTimeText(countdown.at))
}

/** "Takes off in 35 min" / "Lands in 20 min". Day-level countdowns return null: the card's date line says it. */
@Composable
@ReadOnlyComposable
fun countdownSentence(countdown: Countdown?): String? = when (countdown) {
    is Countdown.Departs -> stringResource(R.string.flights_countdown_takes_off, relativeTimeText(countdown.at))
    is Countdown.Lands -> stringResource(R.string.flights_countdown_lands, relativeTimeText(countdown.at))
    is Countdown.DepartsOn, null -> null
}

/** "Today · Wed, 25 Nov" for the three nearest days, else "Wed, 25 Nov". */
@Composable
@ReadOnlyComposable
fun flightDateText(date: LocalDate, day: RelativeDay): String {
    val formatted = FlightTimeFormat.weekdayDayMonth(date)
    return when (day) {
        RelativeDay.Today, RelativeDay.Tomorrow, RelativeDay.Yesterday ->
            stringResource(R.string.flights_joined, relativeDayText(day), formatted)
        is RelativeDay.InDays, is RelativeDay.DaysAgo -> formatted
    }
}

/** The label under a headline time: how sure it is, in travellers' words. */
@Composable
@ReadOnlyComposable
fun timeKindText(kind: TimeKind, isDeparture: Boolean): String = stringResource(
    when (kind) {
        TimeKind.SCHEDULED -> R.string.flights_time_scheduled
        TimeKind.ESTIMATED -> R.string.flights_time_estimated
        TimeKind.ACTUAL -> if (isDeparture) R.string.flights_time_departed else R.string.flights_time_landed
    },
)

/** "Gate 42B", "Belt 5": label and value in one chip. */
@Composable
@ReadOnlyComposable
fun flightInfoChipText(field: FlightField, value: String): String = stringResource(
    when (field) {
        FlightField.DEP_TERMINAL -> R.string.flights_chip_terminal
        FlightField.DEP_GATE -> R.string.flights_chip_gate
        FlightField.CHECK_IN_DESK -> R.string.flights_chip_check_in
        FlightField.ARR_TERMINAL -> R.string.flights_chip_arrival_terminal
        FlightField.ARR_GATE -> R.string.flights_chip_arrival_gate
        FlightField.BAGGAGE_BELT -> R.string.flights_chip_belt
    },
    value,
)

/** "Gate", "Baggage belt": the label of a detail row (the block's heading already says which airport). */
@Composable
@ReadOnlyComposable
fun flightInfoLabel(field: FlightField): String = stringResource(
    when (field) {
        FlightField.DEP_TERMINAL, FlightField.ARR_TERMINAL -> R.string.flights_label_terminal
        FlightField.DEP_GATE, FlightField.ARR_GATE -> R.string.flights_label_gate
        FlightField.CHECK_IN_DESK -> R.string.flights_label_check_in
        FlightField.BAGGAGE_BELT -> R.string.flights_label_belt
    },
)

@Composable
@ReadOnlyComposable
fun cabinText(cabin: Cabin): String = stringResource(
    when (cabin) {
        Cabin.ECONOMY -> R.string.flights_cabin_economy
        Cabin.PREMIUM_ECONOMY -> R.string.flights_cabin_premium_economy
        Cabin.BUSINESS -> R.string.flights_cabin_business
        Cabin.FIRST -> R.string.flights_cabin_first
    },
)

@Composable
@ReadOnlyComposable
fun alertGroupTitle(group: AlertGroup): String = stringResource(
    when (group) {
        AlertGroup.DELAYS -> R.string.flights_alert_delays
        AlertGroup.GATE -> R.string.flights_alert_gate
        AlertGroup.BOARDING -> R.string.flights_alert_boarding
        AlertGroup.TAKEOFF_AND_LANDING -> R.string.flights_alert_takeoff_landing
        AlertGroup.BAGGAGE -> R.string.flights_alert_baggage
        AlertGroup.DISRUPTION -> R.string.flights_alert_disruption
        AlertGroup.INBOUND_AIRCRAFT -> R.string.flights_alert_inbound
        AlertGroup.CHECK_IN -> R.string.flights_alert_checkin
    },
)

@Composable
@ReadOnlyComposable
fun alertGroupDescription(group: AlertGroup): String = stringResource(
    when (group) {
        AlertGroup.DELAYS -> R.string.flights_alert_delays_description
        AlertGroup.GATE -> R.string.flights_alert_gate_description
        AlertGroup.BOARDING -> R.string.flights_alert_boarding_description
        AlertGroup.TAKEOFF_AND_LANDING -> R.string.flights_alert_takeoff_landing_description
        AlertGroup.BAGGAGE -> R.string.flights_alert_baggage_description
        AlertGroup.DISRUPTION -> R.string.flights_alert_disruption_description
        AlertGroup.INBOUND_AIRCRAFT -> R.string.flights_alert_inbound_description
        AlertGroup.CHECK_IN -> R.string.flights_alert_checkin_description
    },
)
