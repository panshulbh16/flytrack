package app.flytrack.core.estimate

import kotlin.time.Instant

/** Stage of the flight according to the clock alone. [wire] is the shared name in docs/P2_CONTRACTS.md. */
enum class EstimatePhase(val wire: String) {
    PRE_DEPARTURE("pre_departure"),
    AIRBORNE("airborne"),
    ARRIVED("arrived"),
}

/**
 * Where the plane should be if it flies the great circle at a steady pace.
 * @property progress share of the route flown, 0..1
 */
data class PositionEstimate(
    val lat: Double,
    val lon: Double,
    val progress: Double,
    val phase: EstimatePhase,
) {
    val point: GeoPoint get() = GeoPoint(lat, lon)
}

/**
 * The shared estimate algorithm (docs/P2_CONTRACTS.md section 5; identical in Swift).
 *
 * * Before [depTime]: `pre_departure`, progress 0, at the departure airport.
 * * From [arrTime] on: `arrived`, progress 1, at the arrival airport.
 * * In between: `airborne`, progress = elapsed / planned block time, and the position is the
 *   great-circle (slerp) interpolation at that progress.
 *
 * A non-positive duration ([arrTime] <= [depTime]) never yields `airborne`: the flight is
 * `pre_departure` until [depTime] and `arrived` afterwards.
 *
 * Callers pick the times as the contract says: `depTime` = act_dep ?: est_dep ?: sched_dep,
 * `arrTime` = est_arr ?: sched_arr (see [app.flytrack.core.estimate.estimateTimes]).
 */
fun estimatePosition(
    dep: GeoPoint,
    arr: GeoPoint,
    depTime: Instant,
    arrTime: Instant,
    now: Instant,
): PositionEstimate {
    val phase = when {
        now < depTime -> EstimatePhase.PRE_DEPARTURE
        now >= arrTime -> EstimatePhase.ARRIVED
        else -> EstimatePhase.AIRBORNE
    }
    val progress = when (phase) {
        EstimatePhase.PRE_DEPARTURE -> 0.0
        EstimatePhase.ARRIVED -> 1.0
        EstimatePhase.AIRBORNE -> {
            val total = (arrTime - depTime).inWholeMilliseconds.toDouble()
            ((now - depTime).inWholeMilliseconds / total).coerceIn(0.0, 1.0)
        }
    }
    val point = GreatCircle.interpolate(dep, arr, progress)
    return PositionEstimate(point.lat, point.lon, progress, phase)
}
