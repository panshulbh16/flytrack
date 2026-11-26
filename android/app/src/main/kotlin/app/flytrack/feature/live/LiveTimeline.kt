package app.flytrack.feature.live

import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.bestArrival
import app.flytrack.core.model.bestDeparture
import app.flytrack.core.model.hasDeparted
import app.flytrack.core.model.hasLanded
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Timing rules of the live notification (contract section 6). Pure Kotlin: no android.* types.
 */
object LiveRules {
    /** The live notification appears this long before the best known departure. */
    val BEFORE_DEPARTURE: Duration = 3.hours

    /** ... and stays this long after the landing (time for the belt). */
    val AFTER_LANDING: Duration = 45.minutes

    /** Boarding usually opens this long before departure (Indian domestic: 45 to 60 min). */
    val BOARDING_LEAD: Duration = 45.minutes

    /** Flight data older than this is no longer trusted: positions past it are an "Estimated" interpolation. */
    val STALE_AFTER: Duration = 30.minutes

    /** A cancelled flight stays the widget's flight until this long after its planned departure. */
    val CANCELLED_VISIBLE_AFTER_DEPARTURE: Duration = 1.hours

    /** The progress bar runs from 0 to this value. */
    const val PROGRESS_MAX = 1000
}

/** One stage of the journey as the live notification shows it. */
enum class LiveStage { CHECK_IN, BOARDING, AIRBORNE, LANDED }

/**
 * The four segments of the progress bar: check-in → boarding → taxi/airborne → landed/belt.
 * [weight] is the segment's share of [LiveRules.PROGRESS_MAX]; the four add up to it.
 */
enum class LiveSegment(val stage: LiveStage, val weight: Int) {
    CHECK_IN(LiveStage.CHECK_IN, 200),
    BOARDING(LiveStage.BOARDING, 100),
    AIRBORNE(LiveStage.AIRBORNE, 550),
    BELT(LiveStage.LANDED, 150),
    ;

    /** Progress value where the segment starts. */
    val start: Int get() = entries.takeWhile { it != this }.sumOf { it.weight }

    /** Progress value where the segment ends. */
    val end: Int get() = start + weight
}

/** A milestone marker on the bar: boarding opens, take-off, landing, belt. */
enum class LivePoint(val position: Int) {
    BOARDING(LiveSegment.BOARDING.start),
    TAKE_OFF(LiveSegment.AIRBORNE.start),
    LANDING(LiveSegment.BELT.start),
    BELT(LiveSegment.BELT.end),
}

/** The times the timeline is built on. */
data class LiveTimes(val departure: Instant, val arrival: Instant) {
    val windowStart: Instant get() = departure - LiveRules.BEFORE_DEPARTURE
    val windowEnd: Instant get() = arrival + LiveRules.AFTER_LANDING
    val boardingStart: Instant get() = departure - LiveRules.BOARDING_LEAD

    operator fun contains(now: Instant): Boolean = now >= windowStart && now < windowEnd
}

/**
 * Departure and arrival the timeline uses: actual, else estimated, else scheduled. null when either
 * is unknown or the arrival is not after the departure (bad data never draws a bar).
 */
fun Flight.liveTimes(): LiveTimes? {
    val dep = bestDeparture ?: return null
    val arr = bestArrival ?: return null
    if (arr <= dep) return null
    return LiveTimes(dep, arr)
}

/** The live window of a flight, null for cancelled flights and flights without usable times. */
fun Flight.liveWindow(): LiveTimes? = if (status == FlightStatus.CANCELLED) null else liveTimes()

/** Where the flight is on the bar and how sure that is. */
data class LiveProgress(
    val stage: LiveStage,
    /** 0..[LiveRules.PROGRESS_MAX]. */
    val position: Int,
    /** True when the position is interpolated from times the data does not confirm (offline, airplane mode). */
    val estimated: Boolean,
) {
    val segments: List<LiveSegment> get() = LiveSegment.entries
    val points: List<LivePoint> get() = LivePoint.entries
}

/** Age of the flight data: newest of the last poll and the last update. null when never stamped. */
fun Flight.dataAge(now: Instant): Duration? {
    val stamp = listOfNotNull(lastPolledAt, updatedAt).maxOrNull() ?: return null
    return now - stamp
}

/** True when the data is too old to trust (or has no time stamp at all). */
fun Flight.isStale(now: Instant): Boolean = dataAge(now)?.let { it > LiveRules.STALE_AFTER } ?: true

/**
 * Progress of [flight] at [now] (contract section 6, offline estimate).
 *
 * The position interpolates between the segment times. Status sets a floor: a flight that is
 * boarding is at least at "boarding", one that departed at least at "take-off", one that landed at
 * least at "landing". While the data is fresh the status also sets a ceiling (no take-off before
 * the data says departed). When the data is stale and time has passed the departure, the ceiling
 * is lifted and the result is marked [LiveProgress.estimated]: this is the airplane-mode estimate.
 *
 * Returns null for flights without a live window (cancelled, no times).
 */
fun liveProgress(flight: Flight, now: Instant): LiveProgress? {
    val times = flight.liveWindow() ?: return null
    val departed = flight.hasDeparted
    val landed = flight.hasLanded
    val boarding = flight.status == FlightStatus.BOARDING || flight.status == FlightStatus.GATE_CLOSED

    val byTime = timePosition(times, now)
    val floor = when {
        landed -> LiveSegment.BELT.start
        departed -> LiveSegment.AIRBORNE.start
        boarding -> LiveSegment.BOARDING.start
        else -> 0
    }
    val estimated = !landed && flight.isStale(now) && (departed || now >= times.departure)
    val ceiling = when {
        estimated || landed -> LiveRules.PROGRESS_MAX
        departed -> LiveSegment.BELT.start - 1
        else -> LiveSegment.AIRBORNE.start - 1
    }
    val position = byTime.coerceAtMost(ceiling).coerceAtLeast(floor)

    val stage = when {
        landed -> LiveStage.LANDED
        departed -> if (position >= LiveSegment.BELT.start) LiveStage.LANDED else LiveStage.AIRBORNE
        estimated -> stageAt(position)
        boarding || position >= LiveSegment.BOARDING.start -> LiveStage.BOARDING
        else -> LiveStage.CHECK_IN
    }
    return LiveProgress(stage, position, estimated)
}

private fun stageAt(position: Int): LiveStage =
    LiveSegment.entries.lastOrNull { position >= it.start }?.stage ?: LiveStage.CHECK_IN

/** Position by time alone. */
internal fun timePosition(times: LiveTimes, now: Instant): Int {
    val bounds = listOf(times.windowStart, times.boardingStart, times.departure, times.arrival, times.windowEnd)
    if (now <= bounds.first()) return 0
    if (now >= bounds.last()) return LiveRules.PROGRESS_MAX
    LiveSegment.entries.forEachIndexed { index, segment ->
        val from = bounds[index]
        val to = bounds[index + 1]
        if (now < to) {
            val span = (to - from).inWholeSeconds
            val fraction = if (span <= 0) 1.0 else (now - from).inWholeSeconds.toDouble() / span
            return segment.start + (fraction.coerceIn(0.0, 1.0) * segment.weight).roundToInt()
        }
    }
    return LiveRules.PROGRESS_MAX
}
