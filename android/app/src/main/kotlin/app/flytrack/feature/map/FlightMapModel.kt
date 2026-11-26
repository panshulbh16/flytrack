package app.flytrack.feature.map

import app.flytrack.core.estimate.EstimatePhase
import app.flytrack.core.estimate.GeoPoint
import app.flytrack.core.estimate.GreatCircle
import app.flytrack.core.estimate.PositionSource
import app.flytrack.core.estimate.position
import app.flytrack.core.estimate.routeEnds
import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightStatus
import kotlin.time.Duration
import kotlin.time.Instant

/** The plane icon: where it is and where its nose points (degrees clockwise from north). */
data class PlaneMarker(val point: GeoPoint, val bearingDeg: Double)

/** What the plane is doing, for the info panel under the map. */
enum class MapFlightState { NOT_DEPARTED, AIRBORNE, ARRIVED, CANCELLED, POSITION_UNKNOWN }

/**
 * Everything the map sheet draws. Paths have unwrapped longitudes (see [GreatCircle.unwrap]).
 * @property flownPath solid part of the route, empty before take-off
 * @property remainingPath dashed part, empty once arrived
 * @property plane null unless the plane is in the air
 * @property source "Live" or "Estimated"; null without a plane
 * @property progress share flown 0..1, null when unknown
 * @property remaining time to the expected touchdown while airborne
 * @property liveObservedAt when a live fix was taken
 */
data class FlightMapUiModel(
    val flightId: String,
    val designator: String,
    val depCode: String,
    val arrCode: String,
    val depCity: String,
    val arrCity: String,
    val dep: GeoPoint,
    val arr: GeoPoint,
    val flownPath: List<GeoPoint>,
    val remainingPath: List<GeoPoint>,
    val plane: PlaneMarker?,
    val state: MapFlightState,
    val source: PositionSource?,
    val progress: Double?,
    val remaining: Duration?,
    val liveObservedAt: Instant?,
    val asOf: Instant,
) {
    /** Points the camera should keep in view: both airports and the plane. */
    val cameraPoints: List<GeoPoint>
        get() = GreatCircle.unwrap(listOfNotNull(dep, plane?.point, arr))
}

/** South-west / north-east corners for the camera, in degrees (east may exceed 180 after unwrapping). */
data class MapBounds(val south: Double, val west: Double, val north: Double, val east: Double)

fun mapBounds(points: List<GeoPoint>): MapBounds? {
    if (points.isEmpty()) return null
    return MapBounds(
        south = points.minOf { it.lat },
        west = points.minOf { it.lon },
        north = points.maxOf { it.lat },
        east = points.maxOf { it.lon },
    )
}

/** The map model at [now]; null while either airport's coordinates are unknown. */
fun Flight.toMapUiModel(now: Instant, segments: Int = GreatCircle.DEFAULT_SEGMENTS): FlightMapUiModel? {
    val ends = routeEnds ?: return null
    val position = position(now)
    val state = when {
        status == FlightStatus.CANCELLED -> MapFlightState.CANCELLED
        position == null -> MapFlightState.POSITION_UNKNOWN
        position.phase == EstimatePhase.PRE_DEPARTURE -> MapFlightState.NOT_DEPARTED
        position.phase == EstimatePhase.ARRIVED -> MapFlightState.ARRIVED
        else -> MapFlightState.AIRBORNE
    }
    val flying = position?.takeIf { state == MapFlightState.AIRBORNE }
    val (flown, remaining) = when {
        flying != null -> splitRoute(ends.dep, ends.arr, flying.progress, flying.point, segments)
        state == MapFlightState.ARRIVED -> GreatCircle.path(ends.dep, ends.arr, segments) to emptyList()
        else -> emptyList<GeoPoint>() to GreatCircle.path(ends.dep, ends.arr, segments)
    }
    return FlightMapUiModel(
        flightId = id,
        designator = designator,
        depCode = depIata,
        arrCode = arrIata,
        depCity = depAirport?.displayCity ?: depIata,
        arrCity = arrAirport?.displayCity ?: arrIata,
        dep = ends.dep,
        arr = ends.arr,
        flownPath = flown,
        remainingPath = remaining,
        plane = flying?.let { PlaneMarker(it.point, it.bearingDeg) },
        state = state,
        source = flying?.source,
        progress = position?.progress,
        remaining = flying?.remaining,
        liveObservedAt = flying?.observedAt,
        asOf = now,
    )
}

/**
 * Splits the great circle at [fraction]. The split point is [at] (the plane, which a live fix
 * may put a little off the ideal line), so both parts meet under the icon.
 */
internal fun splitRoute(
    dep: GeoPoint,
    arr: GeoPoint,
    fraction: Double,
    at: GeoPoint,
    segments: Int = GreatCircle.DEFAULT_SEGMENTS,
): Pair<List<GeoPoint>, List<GeoPoint>> {
    val f = fraction.coerceIn(0.0, 1.0)
    val all = (0..segments).map { it.toDouble() / segments }
    val before = all.filter { it < f }.map { GreatCircle.interpolate(dep, arr, it) }
    val after = all.filter { it > f }.map { GreatCircle.interpolate(dep, arr, it) }
    val flown = before + at
    val rest = listOf(at) + after
    // Unwrap both halves together so they stay continuous across the antimeridian.
    val joined = GreatCircle.unwrap(flown + rest)
    return joined.subList(0, flown.size) to joined.subList(flown.size, joined.size)
}
