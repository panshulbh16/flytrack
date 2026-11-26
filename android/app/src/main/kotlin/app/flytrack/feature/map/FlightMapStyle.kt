package app.flytrack.feature.map

import app.flytrack.core.estimate.GeoPoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Map colours from docs/design-brief.md ("Map"), as `#RRGGBB` strings for the MapLibre style.
 * Dark values are HeyTurbulence's night globe (`globe.js`), which the brief's dark theme comes from.
 */
data class MapPalette(
    val ocean: String,
    val land: String,
    val landOutline: String,
    val graticule: String,
    val route: String,
    val airportFill: String,
    val airportRing: String,
) {
    companion object {
        val Light = MapPalette(
            ocean = "#E2E9DF",
            land = "#3E6B56",
            landOutline = "#527761",
            graticule = "#C2D0C1",
            route = "#DF754E",
            airportFill = "#F6F5EF",
            airportRing = "#DF754E",
        )
        val Dark = MapPalette(
            ocean = "#253E36",
            land = "#8CA890",
            landOutline = "#A0B99F",
            graticule = "#466055",
            route = "#DF754E",
            airportFill = "#F6F5EF",
            airportRing = "#DF754E",
        )

        fun of(dark: Boolean): MapPalette = if (dark) Dark else Light
    }
}

/**
 * The offline MapLibre style, built in code: an ocean background, a 10° graticule, land from the
 * bundled `assets/map/land-110m.geojson` (Natural Earth, public domain) and empty GeoJSON sources
 * for the route, airports and plane that [FlightMapView] fills. There is no tile source, no
 * glyphs and no sprite URL, so nothing is fetched from the network (airplane mode works).
 */
object FlightMapStyle {
    /** Read by MapLibre's asset file source from `src/main/assets/map/`. */
    const val LAND_DATA_URL = "asset://map/land-110m.geojson"

    const val SOURCE_LAND = "land"
    const val SOURCE_GRATICULE = "graticule"
    const val SOURCE_ROUTE_FLOWN = "route-flown"
    const val SOURCE_ROUTE_REMAINING = "route-remaining"
    const val SOURCE_AIRPORTS = "airports"
    const val SOURCE_PLANE = "plane"

    const val LAYER_OCEAN = "ocean"
    const val LAYER_GRATICULE = "graticule"
    const val LAYER_LAND = "land"
    const val LAYER_LAND_OUTLINE = "land-outline"
    const val LAYER_ROUTE_REMAINING = "route-remaining"
    const val LAYER_ROUTE_FLOWN = "route-flown"
    const val LAYER_AIRPORTS = "airports"
    const val LAYER_PLANE = "plane"

    /** Style image id of the plane icon (added from `R.drawable.ic_map_plane`). */
    const val PLANE_IMAGE = "flytrack-plane"

    /** Feature property the plane layer rotates the icon by. */
    const val PROP_BEARING = "bearing"

    const val GRATICULE_STEP_DEG = 10
    private const val GRATICULE_MAX_LAT = 80

    fun styleJson(palette: MapPalette): String = style(palette).toString()

    fun style(palette: MapPalette): JsonObject = buildJsonObject {
        put("version", 8)
        put("name", "FlyTrack offline")
        putJsonObject("sources") {
            put(SOURCE_LAND, geoJsonSource(JsonPrimitive(LAND_DATA_URL)))
            put(SOURCE_GRATICULE, geoJsonSource(graticule()))
            for (id in listOf(SOURCE_ROUTE_REMAINING, SOURCE_ROUTE_FLOWN, SOURCE_AIRPORTS, SOURCE_PLANE)) {
                put(id, geoJsonSource(emptyCollection()))
            }
        }
        putJsonArray("layers") {
            add(buildJsonObject {
                put("id", LAYER_OCEAN)
                put("type", "background")
                putJsonObject("paint") { put("background-color", palette.ocean) }
            })
            add(lineLayer(LAYER_GRATICULE, SOURCE_GRATICULE) {
                put("line-color", palette.graticule)
                put("line-width", 0.65)
            })
            add(buildJsonObject {
                put("id", LAYER_LAND)
                put("type", "fill")
                put("source", SOURCE_LAND)
                putJsonObject("paint") {
                    put("fill-color", palette.land)
                    put("fill-antialias", true)
                }
            })
            add(lineLayer(LAYER_LAND_OUTLINE, SOURCE_LAND) {
                put("line-color", palette.landOutline)
                put("line-width", 0.5)
            })
            add(lineLayer(LAYER_ROUTE_REMAINING, SOURCE_ROUTE_REMAINING, cap = "butt") {
                put("line-color", palette.route)
                put("line-width", 2.5)
                putJsonArray("line-dasharray") {
                    add(2.0)
                    add(1.6)
                }
            })
            add(lineLayer(LAYER_ROUTE_FLOWN, SOURCE_ROUTE_FLOWN, cap = "round") {
                put("line-color", palette.route)
                put("line-width", 3.0)
            })
            add(buildJsonObject {
                put("id", LAYER_AIRPORTS)
                put("type", "circle")
                put("source", SOURCE_AIRPORTS)
                putJsonObject("paint") {
                    put("circle-radius", 5.0)
                    put("circle-color", palette.airportFill)
                    put("circle-stroke-color", palette.airportRing)
                    put("circle-stroke-width", 2.5)
                }
            })
            add(buildJsonObject {
                put("id", LAYER_PLANE)
                put("type", "symbol")
                put("source", SOURCE_PLANE)
                putJsonObject("layout") {
                    put("icon-image", PLANE_IMAGE)
                    putJsonArray("icon-rotate") {
                        add("get")
                        add(PROP_BEARING)
                    }
                    put("icon-rotation-alignment", "map")
                    put("icon-allow-overlap", true)
                    put("icon-ignore-placement", true)
                }
            })
        }
    }

    /** One LineString feature (or an empty collection for fewer than two points). */
    fun lineGeoJson(points: List<GeoPoint>): String =
        if (points.size < 2) {
            emptyCollection().toString()
        } else {
            collection(listOf(feature(lineString(points)))).toString()
        }

    /** One Point feature per airport. */
    fun airportsGeoJson(points: List<GeoPoint>): String =
        collection(points.map { feature(point(it)) }).toString()

    /** The plane as a Point carrying its [PROP_BEARING]; empty when there is no plane to show. */
    fun planeGeoJson(position: GeoPoint?, bearingDeg: Double): String =
        if (position == null) {
            emptyCollection().toString()
        } else {
            collection(listOf(feature(point(position)) { put(PROP_BEARING, bearingDeg) })).toString()
        }

    /** Meridians every [GRATICULE_STEP_DEG] from -180 to 180 and parallels up to ±80°. */
    fun graticule(): JsonObject {
        val lines = mutableListOf<List<GeoPoint>>()
        for (lon in -180..180 step GRATICULE_STEP_DEG) {
            lines += listOf(GeoPoint(-GRATICULE_MAX_LAT.toDouble(), lon.toDouble()), GeoPoint(GRATICULE_MAX_LAT.toDouble(), lon.toDouble()))
        }
        for (lat in -GRATICULE_MAX_LAT..GRATICULE_MAX_LAT step GRATICULE_STEP_DEG) {
            // Points every 10°: a straight line in Web Mercator, but keeps the lines sane if the projection changes.
            lines += (-180..180 step GRATICULE_STEP_DEG).map { GeoPoint(lat.toDouble(), it.toDouble()) }
        }
        return collection(listOf(feature(buildJsonObject {
            put("type", "MultiLineString")
            putJsonArray("coordinates") {
                for (line in lines) addJsonArray { line.forEach { add(position(it)) } }
            }
        })))
    }

    private fun geoJsonSource(data: JsonElement) = buildJsonObject {
        put("type", "geojson")
        put("data", data)
    }

    private fun lineLayer(
        id: String,
        source: String,
        cap: String = "round",
        paint: JsonObjectBuilder.() -> Unit,
    ) = buildJsonObject {
        put("id", id)
        put("type", "line")
        put("source", source)
        putJsonObject("layout") {
            put("line-cap", cap)
            put("line-join", "round")
        }
        putJsonObject("paint", paint)
    }

    private fun emptyCollection(): JsonObject = collection(emptyList())

    private fun collection(features: List<JsonObject>) = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", JsonArray(features))
    }

    private fun feature(
        geometry: JsonObject,
        properties: JsonObjectBuilder.() -> Unit = {},
    ) = buildJsonObject {
        put("type", "Feature")
        putJsonObject("properties", properties)
        put("geometry", geometry)
    }

    private fun point(p: GeoPoint) = buildJsonObject {
        put("type", "Point")
        put("coordinates", position(p))
    }

    private fun lineString(points: List<GeoPoint>) = buildJsonObject {
        put("type", "LineString")
        putJsonArray("coordinates") { points.forEach { add(position(it)) } }
    }

    /** GeoJSON order is [lon, lat]. */
    private fun position(p: GeoPoint) = buildJsonArray {
        add(p.lon)
        add(p.lat)
    }
}
