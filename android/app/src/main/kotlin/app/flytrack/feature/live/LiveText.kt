package app.flytrack.feature.live

import app.flytrack.core.model.DELAY_THRESHOLD_MINUTES
import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightPhase
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.FlightStatusTone
import app.flytrack.core.model.bestArrivalTime
import app.flytrack.core.model.bestDepartureTime
import app.flytrack.core.model.departureDelayMinutes
import app.flytrack.core.model.phase
import app.flytrack.core.model.statusTone
import app.flytrack.core.time.FlightTimeFormat
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** The status word the live notification and the widget show. */
enum class LiveStatusWord { ON_TIME, DELAYED, CHECK_IN, BOARDING, GATE_CLOSED, DEPARTED, IN_AIR, LANDED, CANCELLED, DIVERTED }

/**
 * Localised text, so the formatting below stays pure and testable. The app implements it with
 * `strings_live.xml` ([AndroidLiveStrings]); tests use a plain English fake.
 */
interface LiveStrings {
    fun statusWord(word: LiveStatusWord): String

    /** "Delayed 25 min". */
    fun delayedBy(minutes: Int): String

    /** "DEL → BOM". */
    fun route(dep: String, arr: String): String

    /** "6E 2341 · DEL → BOM". */
    fun title(designator: String, route: String): String

    /** "Departs 18:25". */
    fun departs(time: String): String

    /** "Arrives 20:40". */
    fun arrives(time: String): String

    /** "Landed 20:38". */
    fun landedAt(time: String): String

    /** "Gate 42B". */
    fun gate(gate: String): String

    /** "T1". */
    fun terminal(terminal: String): String

    /** "Belt 5". */
    fun belt(belt: String): String

    /** "Estimated". */
    fun estimated(): String

    /** Joins parts with " · ". */
    fun join(parts: List<String>): String

    /** "3 days", "2h 05m", "35 min" (a span, without "in"). */
    fun countdownDays(days: Int): String

    fun countdownHoursMinutes(hours: Int, minutes: Int): String

    fun countdownMinutes(minutes: Int): String

    /** "Departs in 2h 05m". */
    fun departsIn(span: String): String

    /** "Lands in 35 min". */
    fun landsIn(span: String): String
}

/** Everything the live notification shows, as plain values. Equal content = no re-post. */
data class LiveNotificationContent(
    val flightId: String,
    val title: String,
    val text: String,
    /** Header text next to the app name: "Estimated" for the offline estimate, else null. */
    val subText: String?,
    /** Status-bar chip text of a promoted Live Update (short: a time or the belt). */
    val shortCriticalText: String?,
    val statusWord: LiveStatusWord,
    val tone: FlightStatusTone,
    val progress: LiveProgress,
)

/** Builds the live notification's content. Times are airport-local (`FlightTimeFormat`). */
class LiveContentFormatter(private val strings: LiveStrings) {

    fun notification(flight: Flight, now: Instant): LiveNotificationContent? {
        val progress = liveProgress(flight, now) ?: return null
        val word = statusWord(flight, progress)
        val statusText = statusText(flight, word)
        val details = when (progress.stage) {
            LiveStage.CHECK_IN, LiveStage.BOARDING -> listOfNotNull(
                departureTime(flight)?.let(strings::departs),
                flight.depGate?.let(strings::gate),
                flight.depTerminal?.let(strings::terminal),
            )
            LiveStage.AIRBORNE -> listOfNotNull(
                arrivalTime(flight)?.let(strings::arrives),
                flight.baggageBelt?.let(strings::belt),
            )
            LiveStage.LANDED -> listOfNotNull(
                flight.baggageBelt?.let(strings::belt),
                flight.arrTerminal?.let(strings::terminal),
            )
        }
        // "Landed 20:35" says both the status and the time.
        val headline = if (word == LiveStatusWord.LANDED) arrivalTime(flight)?.let(strings::landedAt) ?: statusText else statusText
        val chip = when (progress.stage) {
            LiveStage.CHECK_IN, LiveStage.BOARDING -> departureTime(flight)
            LiveStage.AIRBORNE -> arrivalTime(flight)
            LiveStage.LANDED -> flight.baggageBelt?.let(strings::belt)
        }
        return LiveNotificationContent(
            flightId = flight.id,
            title = title(flight),
            text = strings.join(listOf(headline) + details),
            subText = if (progress.estimated) strings.estimated() else null,
            shortCriticalText = chip,
            statusWord = word,
            tone = if (progress.estimated) FlightStatusTone.ESTIMATED else flight.statusTone,
            progress = progress,
        )
    }

    fun title(flight: Flight): String = strings.title(flight.designator, route(flight))

    fun route(flight: Flight): String = strings.route(flight.depIata, flight.arrIata)

    /** The status word, with the delay for a late departure ("Delayed 25 min"). */
    fun statusText(flight: Flight, word: LiveStatusWord): String {
        val delay = flight.departureDelayMinutes
        return if (word == LiveStatusWord.DELAYED && delay != null) strings.delayedBy(delay) else strings.statusWord(word)
    }

    /** Best departure time, airport-local ("18:25"). */
    fun departureTime(flight: Flight): String? = flight.bestDepartureTime?.let { FlightTimeFormat.time(it.instant, flight.depTz) }

    /** Best arrival time, airport-local ("20:40"). */
    fun arrivalTime(flight: Flight): String? = flight.bestArrivalTime?.let { FlightTimeFormat.time(it.instant, flight.arrTz) }

    /** "2h 05m", "3 days", "35 min"; null when [span] is not positive. */
    fun countdown(span: Duration): String? {
        if (!span.isPositive()) return null
        return when {
            span >= 2.days -> strings.countdownDays(span.inWholeDays.toInt())
            span >= 1.hours -> {
                val minutes = span.inWholeMinutes
                strings.countdownHoursMinutes((minutes / 60).toInt(), (minutes % 60).toInt())
            }
            else -> strings.countdownMinutes(span.inWholeMinutes.toInt().coerceAtLeast(1))
        }
    }

    companion object {
        /**
         * Status word for a flight. [progress] (when in the live window) decides the stage, so the
         * offline estimate can say "In the air" before a stale status does.
         */
        fun statusWord(flight: Flight, progress: LiveProgress?): LiveStatusWord {
            when (flight.status) {
                FlightStatus.CANCELLED -> return LiveStatusWord.CANCELLED
                FlightStatus.DIVERTED -> return LiveStatusWord.DIVERTED
                else -> Unit
            }
            val delayed = (flight.departureDelayMinutes ?: 0) >= DELAY_THRESHOLD_MINUTES
            val stage = progress?.stage ?: when (flight.phase) {
                FlightPhase.LANDED -> LiveStage.LANDED
                FlightPhase.IN_AIR -> LiveStage.AIRBORNE
                FlightPhase.BOARDING -> LiveStage.BOARDING
                else -> LiveStage.CHECK_IN
            }
            val estimated = progress?.estimated == true
            return when (stage) {
                LiveStage.LANDED -> LiveStatusWord.LANDED
                LiveStage.AIRBORNE ->
                    if (flight.status == FlightStatus.DEPARTED && !estimated) LiveStatusWord.DEPARTED else LiveStatusWord.IN_AIR
                LiveStage.BOARDING -> when {
                    flight.status == FlightStatus.GATE_CLOSED -> LiveStatusWord.GATE_CLOSED
                    flight.status == FlightStatus.BOARDING -> LiveStatusWord.BOARDING
                    delayed -> LiveStatusWord.DELAYED
                    else -> LiveStatusWord.ON_TIME
                }
                LiveStage.CHECK_IN -> when {
                    delayed -> LiveStatusWord.DELAYED
                    flight.status == FlightStatus.CHECK_IN -> LiveStatusWord.CHECK_IN
                    else -> LiveStatusWord.ON_TIME
                }
            }
        }
    }
}
