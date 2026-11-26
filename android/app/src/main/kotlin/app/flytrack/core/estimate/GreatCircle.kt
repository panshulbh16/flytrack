package app.flytrack.core.estimate

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A position in degrees (WGS 84). */
data class GeoPoint(val lat: Double, val lon: Double)

/**
 * Spherical geometry for routes: distance, slerp interpolation and bearings on a sphere of
 * radius [EARTH_RADIUS_KM]. Pure Kotlin; the Swift twin (`FlyTrackCore`) uses the same formulas
 * so both apps place the plane at the same spot (docs/P2_CONTRACTS.md section 5).
 */
object GreatCircle {
    const val EARTH_RADIUS_KM = 6371.0

    /** Central angle between [a] and [b], in radians (haversine). */
    fun angle(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        return 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun distanceKm(a: GeoPoint, b: GeoPoint): Double = angle(a, b) * EARTH_RADIUS_KM

    /**
     * The point a [fraction] (0..1, clamped) of the way from [a] to [b] along the great circle
     * (spherical linear interpolation of the two unit vectors).
     */
    fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double): GeoPoint {
        val f = fraction.coerceIn(0.0, 1.0)
        if (f == 0.0) return a
        if (f == 1.0) return b
        val d = angle(a, b)
        if (d < 1e-12) return a
        val lat1 = Math.toRadians(a.lat)
        val lon1 = Math.toRadians(a.lon)
        val lat2 = Math.toRadians(b.lat)
        val lon2 = Math.toRadians(b.lon)
        val wa = sin((1 - f) * d) / sin(d)
        val wb = sin(f * d) / sin(d)
        val x = wa * cos(lat1) * cos(lon1) + wb * cos(lat2) * cos(lon2)
        val y = wa * cos(lat1) * sin(lon1) + wb * cos(lat2) * sin(lon2)
        val z = wa * sin(lat1) + wb * sin(lat2)
        return GeoPoint(
            lat = Math.toDegrees(atan2(z, sqrt(x * x + y * y))),
            lon = Math.toDegrees(atan2(y, x)),
        )
    }

    /** Initial bearing (forward azimuth) from [a] towards [b], degrees clockwise from north in 0..<360. */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return normalizeDegrees(Math.toDegrees(atan2(y, x)))
    }

    /**
     * The heading of a plane a [fraction] of the way along the route: the bearing from that point
     * towards [b], or at the very end the final bearing (the course on arrival).
     */
    fun bearingAt(a: GeoPoint, b: GeoPoint, fraction: Double): Double {
        val f = fraction.coerceIn(0.0, 1.0)
        if (angle(a, b) < 1e-12) return 0.0
        if (f >= 1.0) return normalizeDegrees(bearing(b, a) + 180.0)
        return bearing(interpolate(a, b, f), b)
    }

    /**
     * How far along the route from [a] to [b] the point [p] is (0..1): the share of the distance
     * already covered, which suits a live fix a little off the ideal great circle.
     */
    fun fractionAlong(a: GeoPoint, b: GeoPoint, p: GeoPoint): Double {
        val flown = angle(a, p)
        val left = angle(p, b)
        val sum = flown + left
        if (sum < 1e-12) return 0.0
        return (flown / sum).coerceIn(0.0, 1.0)
    }

    /**
     * [segments] + 1 points along the great circle from [a] to [b], both ends included.
     * Longitudes are unwrapped (they may leave -180..180) so a line drawn through them never
     * jumps across the whole map at the antimeridian.
     */
    fun path(a: GeoPoint, b: GeoPoint, segments: Int = DEFAULT_SEGMENTS): List<GeoPoint> {
        require(segments >= 1) { "segments must be >= 1" }
        return unwrap((0..segments).map { interpolate(a, b, it.toDouble() / segments) })
    }

    /** Shifts longitudes by multiples of 360 so consecutive points are never more than 180 apart. */
    fun unwrap(points: List<GeoPoint>): List<GeoPoint> {
        if (points.size < 2) return points
        val out = ArrayList<GeoPoint>(points.size)
        out += points[0]
        for (i in 1 until points.size) {
            var lon = points[i].lon
            val previous = out[i - 1].lon
            while (lon - previous > 180) lon -= 360
            while (lon - previous < -180) lon += 360
            out += GeoPoint(points[i].lat, lon)
        }
        return out
    }

    const val DEFAULT_SEGMENTS = 64

    private fun normalizeDegrees(deg: Double): Double {
        val r = deg % 360.0
        val n = if (r < 0) r + 360.0 else r
        return if (abs(n - 360.0) < 1e-9) 0.0 else n
    }
}
