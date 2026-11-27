package app.flytrack.feature.radar

import app.flytrack.core.estimate.GeoPoint
import app.flytrack.feature.map.FlightMapStyle
import app.flytrack.feature.map.MapPalette
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.PI
import kotlin.math.cos

/**
 * The radar's MapLibre style: the flight map's offline style ([FlightMapStyle]: ocean, graticule, bundled
 * land; no tiles, glyphs or sprite) with the radar's own sources and layers on top. The flight map's route
 * and plane layers stay empty.
 *
 * Labels are images, not text: the style has no glyphs (nothing may be fetched), so the view renders each
 * callsign into a small bitmap and registers it under [labelImageId].
 */
object RadarMapStyle {
    const val SOURCE_RANGE = "radar-range"
    const val SOURCE_PLANES = "radar-planes"
    const val SOURCE_CENTER = "radar-center"

    const val LAYER_RANGE_FILL = "radar-range-fill"
    const val LAYER_RANGE_LINE = "radar-range-line"
    const val LAYER_CENTER = "radar-center"
    const val LAYER_LABELS = "radar-labels"
    const val LAYER_PLANES = "radar-planes"

    const val PROP_BEARING = "bearing"
    const val PROP_LABEL = "label"
    const val PROP_GROUND = "ground"
    const val PROP_KEY = "key"

    /** Same plane drawable as the flight map ([FlightMapStyle.PLANE_IMAGE]). */
    const val PLANE_IMAGE = FlightMapStyle.PLANE_IMAGE

    private const val LABEL_PREFIX = "radar-label:"
    private const val CIRCLE_SEGMENTS = 64
    private const val KM_PER_DEG_LAT = 111.32

    fun labelImageId(label: String): String = LABEL_PREFIX + label

    fun styleJson(palette: MapPalette): String = style(palette).toString()

    fun style(palette: MapPalette): JsonObject {
        val base = FlightMapStyle.style(palette)
        val sources = buildJsonObject {
            base["sources"]!!.jsonObject.forEach { (k, v) -> put(k, v) }
            put(SOURCE_RANGE, geoJsonSource(emptyCollection()))
            put(SOURCE_CENTER, geoJsonSource(emptyCollection()))
            put(SOURCE_PLANES, geoJsonSource(emptyCollection()))
        }
        val layers = buildJsonArray {
            base["layers"]!!.jsonArray.forEach { add(it) }
            add(buildJsonObject {
                put("id", LAYER_RANGE_FILL)
                put("type", "fill")
                put("source", SOURCE_RANGE)
                putJsonObject("paint") {
                    put("fill-color", palette.route)
                    put("fill-opacity", 0.06)
                }
            })
            add(buildJsonObject {
                put("id", LAYER_RANGE_LINE)
                put("type", "line")
                put("source", SOURCE_RANGE)
                putJsonObject("paint") {
                    put("line-color", palette.route)
                    put("line-width", 1.2)
                    putJsonArray("line-dasharray") {
                        add(2.0)
                        add(2.0)
                    }
                }
            })
            add(buildJsonObject {
                put("id", LAYER_CENTER)
                put("type", "circle")
                put("source", SOURCE_CENTER)
                putJsonObject("paint") {
                    put("circle-radius", 5.0)
                    put("circle-color", palette.airportFill)
                    put("circle-stroke-color", palette.airportRing)
                    put("circle-stroke-width", 2.5)
                }
            })
            add(buildJsonObject {
                put("id", LAYER_LABELS)
                put("type", "symbol")
                put("source", SOURCE_PLANES)
                putJsonObject("layout") {
                    putJsonArray("icon-image") {
                        add("get")
                        add(PROP_LABEL)
                    }
                    put("icon-anchor", "top")
                    putJsonArray("icon-offset") {
                        add(0)
                        add(16)
                    }
                    // Labels may hide each other when crowded; the planes themselves never do.
                    put("icon-allow-overlap", false)
                    put("icon-optional", true)
                }
            })
            add(buildJsonObject {
                put("id", LAYER_PLANES)
                put("type", "symbol")
                put("source", SOURCE_PLANES)
                putJsonObject("layout") {
                    put("icon-image", PLANE_IMAGE)
                    putJsonArray("icon-rotate") {
                        add("get")
                        add(PROP_BEARING)
                    }
                    // Aircraft on the ground are drawn a little smaller.
                    putJsonArray("icon-size") {
                        add("case")
                        addJsonArray {
                            add("get")
                            add(PROP_GROUND)
                        }
                        add(0.75)
                        add(1.0)
                    }
                    put("icon-rotation-alignment", "map")
                    put("icon-allow-overlap", true)
                    put("icon-ignore-placement", true)
                }
            })
        }
        return buildJsonObject {
            base.forEach { (k, v) ->
                when (k) {
                    "sources" -> put(k, sources)
                    "layers" -> put(k, layers)
                    else -> put(k, v)
                }
            }
            put("name", "FlyTrack radar (offline)")
        }
    }

    /** One Point per aircraft with its bearing, label image id, ground flag and key. */
    fun planesGeoJson(rows: List<RadarAircraftUiModel>): String = collection(
        rows.map { row ->
            feature(point(row.position)) {
                put(PROP_BEARING, row.trackDeg)
                put(PROP_LABEL, labelImageId(row.label))
                put(PROP_GROUND, row.onGround)
                put(PROP_KEY, row.key)
            }
        },
    ).toString()

    fun centerGeoJson(center: GeoPoint?): String =
        if (center == null) emptyCollection().toString() else collection(listOf(feature(point(center)))).toString()

    /** The search radius as a closed ring (equirectangular approximation, fine at 75 km). */
    fun rangeGeoJson(center: GeoPoint?, radiusKm: Int): String {
        if (center == null || radiusKm <= 0) return emptyCollection().toString()
        val ring = circle(center, radiusKm.toDouble())
        return collection(listOf(feature(buildJsonObject {
            put("type", "Polygon")
            putJsonArray("coordinates") { addJsonArray { ring.forEach { add(position(it)) } } }
        }))).toString()
    }

    /** Bounds (south, west, north, east) that show the whole radius. */
    fun bounds(center: GeoPoint, radiusKm: Int): DoubleArray {
        val dLat = radiusKm / KM_PER_DEG_LAT
        val dLon = radiusKm / (KM_PER_DEG_LAT * cos(center.lat * PI / 180.0).coerceAtLeast(0.01))
        return doubleArrayOf(
            (center.lat - dLat).coerceAtLeast(-85.0),
            center.lon - dLon,
            (center.lat + dLat).coerceAtMost(85.0),
            center.lon + dLon,
        )
    }

    internal fun circle(center: GeoPoint, radiusKm: Double): List<GeoPoint> {
        val dLat = radiusKm / KM_PER_DEG_LAT
        val dLon = radiusKm / (KM_PER_DEG_LAT * cos(center.lat * PI / 180.0).coerceAtLeast(0.01))
        val points = (0 until CIRCLE_SEGMENTS).map { i ->
            val a = 2 * PI * i / CIRCLE_SEGMENTS
            GeoPoint(center.lat + dLat * kotlin.math.sin(a), center.lon + dLon * cos(a))
        }
        return points + points.first()
    }

    private fun geoJsonSource(data: JsonElement) = buildJsonObject {
        put("type", "geojson")
        put("data", data)
    }

    private fun emptyCollection(): JsonObject = collection(emptyList())

    private fun collection(features: List<JsonObject>) = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", JsonArray(features))
    }

    private fun feature(geometry: JsonObject, properties: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("type", "Feature")
        putJsonObject("properties", properties)
        put("geometry", geometry)
    }

    private fun point(p: GeoPoint) = buildJsonObject {
        put("type", "Point")
        put("coordinates", position(p))
    }

    private fun position(p: GeoPoint) = buildJsonArray {
        add(JsonPrimitive(p.lon))
        add(JsonPrimitive(p.lat))
    }
}
