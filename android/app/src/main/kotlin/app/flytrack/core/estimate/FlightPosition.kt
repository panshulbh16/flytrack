package app.flytrack.core.estimate

import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightPhase
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.LivePosition
import app.flytrack.core.model.hasDeparted
import app.flytrack.core.model.hasLanded
import app.flytrack.core.model.phase
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Where a position comes from; the UI labels it "Live" or "Estimated". */
enum class PositionSource { LIVE, ESTIMATED }

object EstimateRules {
    /** A live fix younger than this is shown as "Live" (docs/P2_CONTRACTS.md section 5). */
    val LIVE_FRESH_FOR: Duration = 20.minutes

    /** A fix stamped this far in the future (clock skew) still counts as fresh. */
    val LIVE_CLOCK_SKEW: Duration = 5.minutes

    /**
     * Flight data not checked by the backend for longer than this is no longer trusted to say
     * "not departed yet": past the departure time the card then shows the estimate (airplane mode).
     * Same value as the live notification's `LiveRules.STALE_AFTER`.
     */
    val DATA_STALE_AFTER: Duration = 30.minutes
}

/** The two times the estimate runs between. */
data class EstimateTimes(val departure: Instant, val arrival: Instant)

/** The two ends of the route. */
data class RouteEnds(val dep: GeoPoint, val arr: GeoPoint)

/** `depTime` = act_dep ?: est_dep ?: sched_dep and `arrTime` = est_arr ?: sched_arr; null when either is unknown. */
val Flight.estimateTimes: EstimateTimes?
    get() {
        val dep = actDep ?: estDep ?: schedDep ?: return null
        val arr = estArr ?: schedArr ?: return null
        return EstimateTimes(dep, arr)
    }

/** The airports' coordinates; null while either airport is unknown. */
val Flight.routeEnds: RouteEnds?
    get() {
        val a = depAirport ?: return null
        val b = arrAirport ?: return null
        return RouteEnds(GeoPoint(a.lat, a.lon), GeoPoint(b.lat, b.lon))
    }

/** The live fix when it is fresher than [EstimateRules.LIVE_FRESH_FOR] and has sane coordinates, else null. */
fun Flight.freshLivePosition(now: Instant): LivePosition? {
    val fix = live ?: return null
    val at = fix.at ?: return null
    if (fix.lat !in -90.0..90.0 || fix.lon !in -180.0..180.0) return null
    if (fix.lat == 0.0 && fix.lon == 0.0) return null // "null island": a provider's missing value
    val age = now - at
    return fix.takeIf { age <= EstimateRules.LIVE_FRESH_FOR && age >= -EstimateRules.LIVE_CLOCK_SKEW }
}

/** When the backend last checked or changed this flight; null when never. */
private val Flight.dataAt: Instant?
    get() = listOfNotNull(lastPolledAt, updatedAt).maxOrNull()

/** True when the backend has not confirmed the flight for [EstimateRules.DATA_STALE_AFTER] (or never). */
fun Flight.isDataStale(now: Instant): Boolean =
    dataAt?.let { now - it > EstimateRules.DATA_STALE_AFTER } ?: true

/**
 * The plane's position for the map.
 * @property bearingDeg heading, degrees clockwise from north
 * @property progress share of the route flown, 0..1
 * @property remaining time until the expected arrival; null once arrived or when unknown
 * @property observedAt when the live fix was taken; null for an estimate
 */
data class FlightPosition(
    val point: GeoPoint,
    val bearingDeg: Double,
    val progress: Double,
    val phase: EstimatePhase,
    val source: PositionSource,
    val remaining: Duration?,
    val observedAt: Instant?,
)

/**
 * The best position at [now]: a fresh live fix ("Live"), otherwise the shared estimate
 * ("Estimated"), reconciled with what the status already says for certain:
 *
 * * cancelled, or the route unknown: null;
 * * landed: arrived at the destination;
 * * diverted: only a live fix (the estimate would fly to the wrong airport);
 * * departed while the clock says not yet (a late take-off not yet in the times): airborne at 0.
 */
fun Flight.position(now: Instant): FlightPosition? {
    if (status == FlightStatus.CANCELLED) return null
    val ends = routeEnds ?: return null
    val times = estimateTimes
    val remaining = times?.arrival?.let { (it - now).coerceAtLeast(Duration.ZERO) }

    if (!hasLanded) {
        freshLivePosition(now)?.let { fix ->
            val point = GeoPoint(fix.lat, fix.lon)
            return FlightPosition(
                point = point,
                bearingDeg = fix.headingDeg?.takeIf { it.isFinite() }?.let { ((it % 360) + 360) % 360 }
                    ?: GreatCircle.bearing(point, ends.arr),
                progress = GreatCircle.fractionAlong(ends.dep, ends.arr, point),
                phase = EstimatePhase.AIRBORNE,
                source = PositionSource.LIVE,
                remaining = remaining,
                observedAt = fix.at,
            )
        }
    }
    if (status == FlightStatus.DIVERTED) return null
    if (hasLanded) {
        return FlightPosition(
            point = ends.arr,
            bearingDeg = GreatCircle.bearingAt(ends.dep, ends.arr, 1.0),
            progress = 1.0,
            phase = EstimatePhase.ARRIVED,
            source = PositionSource.ESTIMATED,
            remaining = null,
            observedAt = null,
        )
    }
    times ?: return null
    var estimate = estimatePosition(ends.dep, ends.arr, times.departure, times.arrival, now)
    val trusted = !isDataStale(now)
    if (hasDeparted && estimate.phase == EstimatePhase.PRE_DEPARTURE) {
        estimate = estimate.copy(phase = EstimatePhase.AIRBORNE)
    } else if (!hasDeparted && trusted && estimate.phase != EstimatePhase.PRE_DEPARTURE) {
        // Fresh data still says "not departed" (a late departure not yet in the times): the
        // plane is at the gate, matching the card, which shows no progress line (progressFix).
        estimate = PositionEstimate(ends.dep.lat, ends.dep.lon, 0.0, EstimatePhase.PRE_DEPARTURE)
    } else if (hasDeparted && trusted && estimate.phase == EstimatePhase.ARRIVED) {
        // Fresh data says still in the air past the planned arrival: over the destination, not landed.
        estimate = estimate.copy(phase = EstimatePhase.AIRBORNE)
    }
    return FlightPosition(
        point = estimate.point,
        bearingDeg = GreatCircle.bearingAt(ends.dep, ends.arr, estimate.progress),
        progress = estimate.progress,
        phase = estimate.phase,
        source = PositionSource.ESTIMATED,
        remaining = remaining.takeIf { estimate.phase != EstimatePhase.ARRIVED },
        observedAt = null,
    )
}

/**
 * The progress line of the flight card and detail screen.
 * @property progress share flown, 0..1
 * @property arrival the expected touchdown the remaining time counts down to
 */
data class ProgressFix(val progress: Float, val source: PositionSource, val arrival: Instant?)

/**
 * Progress for the card and detail screen at [now], null when no progress line belongs on them.
 *
 * * In the air (by status): a fresh live fix gives the share of the distance flown ("Live"),
 *   otherwise the share of the planned time ("Estimated"; needs no airport coordinates).
 * * Not departed by status, but the data is stale and the clock is between departure and arrival:
 *   the offline estimate ("Estimated"), so airplane mode still shows the flight moving.
 * * Anything else (landed, cancelled, diverted, before take-off): null.
 */
fun Flight.progressFix(now: Instant): ProgressFix? {
    val times = estimateTimes
    return when (phase) {
        FlightPhase.IN_AIR -> {
            val ends = routeEnds
            val fix = freshLivePosition(now)
            if (fix != null && ends != null) {
                val progress = GreatCircle.fractionAlong(ends.dep, ends.arr, GeoPoint(fix.lat, fix.lon))
                ProgressFix(progress.toFloat(), PositionSource.LIVE, times?.arrival)
            } else {
                times?.let { timeProgress(it, now) }?.let { ProgressFix(it, PositionSource.ESTIMATED, times.arrival) }
            }
        }
        FlightPhase.UPCOMING, FlightPhase.CHECK_IN, FlightPhase.BOARDING -> {
            if (times == null || !isDataStale(now)) return null
            if (now < times.departure || now >= times.arrival) return null
            timeProgress(times, now)?.let { ProgressFix(it, PositionSource.ESTIMATED, times.arrival) }
        }
        FlightPhase.LANDED, FlightPhase.CANCELLED, FlightPhase.DIVERTED -> null
    }
}

private fun timeProgress(times: EstimateTimes, now: Instant): Float? {
    val total = (times.arrival - times.departure).inWholeSeconds
    if (total <= 0) return null
    return ((now - times.departure).inWholeSeconds.toFloat() / total).coerceIn(0f, 1f)
}
