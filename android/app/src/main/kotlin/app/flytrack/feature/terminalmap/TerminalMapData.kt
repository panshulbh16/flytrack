package app.flytrack.feature.terminalmap

import app.flytrack.core.estimate.GeoPoint
import app.flytrack.core.estimate.GreatCircle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Terminal maps (docs/P4_CONTRACTS.md section 4): one simplified OpenStreetMap GeoJSON per airport, bundled
 * at `assets/terminal-maps/{IATA}.geojson` (format in data/terminal-maps/README.md). Pure Kotlin, JVM-tested.
 */
object TerminalMapContract {
    const val ASSET_DIR = "terminal-maps"

    /** Must be visible on every terminal map (ODbL). Also the files' own `attribution` field. */
    const val ATTRIBUTION = "© OpenStreetMap contributors (ODbL)"

    /** The airports the build script covers; others say "not available yet" without a button. */
    val PLANNED_AIRPORTS = setOf("DEL", "BOM", "BLR", "HYD", "MAA", "CCU", "COK", "AMD")

    fun assetPath(iata: String): String = "$ASSET_DIR/${iata.uppercase()}.geojson"
    fun assetUrl(iata: String): String = "asset://${assetPath(iata)}"
}

/** The `kind` property of a feature. */
enum class TerminalKind(val wire: String, val isPoi: Boolean = false) {
    TERMINAL("terminal"),
    APRON("apron"),
    RUNWAY("runway"),
    TAXIWAY("taxiway"),
    JET_BRIDGE("jet_bridge"),
    BAGGAGE_CLAIM("baggage_claim"),
    PARKING_POSITION("parking_position"),
    GATE("gate"),
    FOOD("food", isPoi = true),
    TOILETS("toilets", isPoi = true),
    LOUNGE("lounge", isPoi = true),
    ATM("atm", isPoi = true),
    PRAYER_ROOM("prayer_room", isPoi = true),
    PHARMACY("pharmacy", isPoi = true),
    CHARGING("charging", isPoi = true),
    SHOP("shop", isPoi = true),
    ;

    companion object {
        fun fromWire(value: String?): TerminalKind? = entries.firstOrNull { it.wire == value }

        /** The order of the POI list and its filter chips: what travellers look for first. */
        val POI_ORDER = listOf(FOOD, TOILETS, LOUNGE, ATM, PRAYER_ROOM, PHARMACY, CHARGING, SHOP)
    }
}

/** A gate, a POI or a belt area, reduced to one point. */
data class TerminalPlace(
    val id: String,
    val kind: TerminalKind,
    val ref: String?,
    val name: String?,
    val level: String?,
    val point: GeoPoint,
)

/** South-west and north-east corners. */
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    val center: GeoPoint get() = GeoPoint((south + north) / 2, (west + east) / 2)
}

data class TerminalMapData(
    val iata: String,
    val attribution: String,
    val generatedAt: String?,
    val bounds: GeoBounds?,
    /** Bounds of the terminal buildings only, for the first camera position. */
    val terminalBounds: GeoBounds?,
    val gates: List<TerminalPlace>,
    val pois: List<TerminalPlace>,
    val beltAreas: List<TerminalPlace>,
    val featureCount: Int,
)

object TerminalMapParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Parses one bundled file. Throws [IllegalArgumentException] (or a serialization exception) on a bad file. */
    fun parse(text: String, iataHint: String? = null): TerminalMapData {
        val root = json.parseToJsonElement(text).jsonObject
        require(root.string("type") == "FeatureCollection") { "not a FeatureCollection" }
        val features = root["features"]?.jsonArray ?: JsonArray(emptyList())
        val gates = mutableListOf<TerminalPlace>()
        val pois = mutableListOf<TerminalPlace>()
        val belts = mutableListOf<TerminalPlace>()
        val all = BoundsBuilder()
        val terminals = BoundsBuilder()
        features.forEachIndexed { index, element ->
            val feature = element as? JsonObject ?: return@forEachIndexed
            val props = feature["properties"] as? JsonObject ?: JsonObject(emptyMap())
            val kind = TerminalKind.fromWire(props.string("kind")) ?: return@forEachIndexed
            val geometry = feature["geometry"] as? JsonObject ?: return@forEachIndexed
            val coords = mutableListOf<GeoPoint>()
            collectPositions(geometry["coordinates"], coords)
            if (coords.isEmpty()) return@forEachIndexed
            coords.forEach(all::add)
            if (kind == TerminalKind.TERMINAL) coords.forEach(terminals::add)
            val place = TerminalPlace(
                id = (feature["id"] as? JsonPrimitive)?.contentOrNull ?: "f$index",
                kind = kind,
                ref = props.string("ref")?.trim()?.ifEmpty { null },
                name = props.string("name")?.trim()?.ifEmpty { null },
                level = props.string("level")?.trim()?.ifEmpty { null },
                point = centroid(coords),
            )
            when {
                kind == TerminalKind.GATE -> gates += place
                kind == TerminalKind.BAGGAGE_CLAIM -> belts += place
                kind.isPoi -> pois += place
            }
        }
        return TerminalMapData(
            iata = (root.string("airport") ?: iataHint.orEmpty()).uppercase(),
            attribution = root.string("attribution") ?: TerminalMapContract.ATTRIBUTION,
            generatedAt = root.string("generated_at"),
            bounds = all.build(),
            terminalBounds = terminals.build(),
            gates = gates,
            pois = pois,
            beltAreas = belts,
            featureCount = features.size,
        )
    }

    /** Every `[lon, lat]` position in a GeoJSON `coordinates` value, whatever its nesting. */
    private fun collectPositions(element: JsonElement?, out: MutableList<GeoPoint>) {
        val array = element as? JsonArray ?: return
        val first = array.firstOrNull()
        if (first is JsonPrimitive) {
            val lon = (array.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return
            val lat = (array.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return
            out += GeoPoint(lat, lon)
        } else {
            array.forEach { collectPositions(it, out) }
        }
    }

    /** Mean of the positions; a closed ring's repeated first point is counted once. */
    private fun centroid(points: List<GeoPoint>): GeoPoint {
        val pts = if (points.size > 2 && points.first() == points.last()) points.dropLast(1) else points
        return GeoPoint(pts.sumOf { it.lat } / pts.size, pts.sumOf { it.lon } / pts.size)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private class BoundsBuilder {
        private var south = Double.POSITIVE_INFINITY
        private var west = Double.POSITIVE_INFINITY
        private var north = Double.NEGATIVE_INFINITY
        private var east = Double.NEGATIVE_INFINITY
        fun add(p: GeoPoint) {
            south = minOf(south, p.lat)
            north = maxOf(north, p.lat)
            west = minOf(west, p.lon)
            east = maxOf(east, p.lon)
        }
        fun build(): GeoBounds? = if (south.isInfinite()) null else GeoBounds(south, west, north, east)
    }
}

/** Gate and belt matching, and the POI list. */
object TerminalMapLogic {

    /**
     * "Gate 42B", "42 b", "042B" and "42B" are the same gate. Words like "Gate", "Belt", "No." and spaces,
     * dashes and dots are dropped; leading zeros of the number are dropped.
     */
    fun normalizeRef(value: String?): String? {
        if (value == null) return null
        var s = value.uppercase().trim()
        s = s.replace(PREFIX_WORDS, "")
        s = s.filter { it.isLetterOrDigit() }
        if (s.isEmpty()) return null
        // Leading zeros of a leading number: "07" -> "7", "007A" -> "7A"; "0" stays "0".
        s = s.replace(LEADING_ZEROS, "")
        return s.ifEmpty { "0" }
    }

    /** The gate on the map for the flight's `dep_gate`, or null when the map has no such gate. */
    fun findGate(gates: List<TerminalPlace>, gate: String?): TerminalPlace? {
        val wanted = normalizeRef(gate) ?: return null
        gates.firstOrNull { normalizeRef(it.ref) == wanted }?.let { return it }
        // Some gates are mapped by name only ("Gate 12").
        return gates.firstOrNull { it.ref == null && normalizeRef(it.name) == wanted }
    }

    /** The belt area for the flight's `baggage_belt`; when only one unnamed belt hall is mapped, that one. */
    fun findBelt(areas: List<TerminalPlace>, belt: String?): TerminalPlace? {
        val wanted = normalizeRef(belt) ?: return null
        areas.firstOrNull { normalizeRef(it.ref) == wanted || (it.ref == null && normalizeRef(it.name) == wanted) }?.let { return it }
        return areas.singleOrNull()?.takeIf { it.ref == null }
    }

    /**
     * The POI list: optionally one [category], filtered by [query] against the name and the category's own
     * word ([kindLabel]: "Food", "Toilets"…), nearest to [near] first when given (the user's gate), else by
     * category order and name.
     */
    fun filterPois(
        pois: List<TerminalPlace>,
        query: String,
        category: TerminalKind?,
        kindLabel: (TerminalKind) -> String,
        near: GeoPoint? = null,
    ): List<TerminalPlace> {
        val q = query.trim().lowercase()
        return pois
            .asSequence()
            .filter { category == null || it.kind == category }
            .filter { poi ->
                q.isEmpty() ||
                    poi.name?.lowercase()?.contains(q) == true ||
                    kindLabel(poi.kind).lowercase().contains(q) ||
                    poi.kind.wire.replace('_', ' ').contains(q)
            }
            .sortedWith(
                if (near != null) {
                    compareBy<TerminalPlace> { GreatCircle.distanceKm(near, it.point) }.thenBy { it.name ?: "" }
                } else {
                    compareBy<TerminalPlace> { TerminalKind.POI_ORDER.indexOf(it.kind) }.thenBy { it.name?.lowercase() ?: "￿" }
                },
            )
            .toList()
    }

    /** The categories that have at least one POI, in [TerminalKind.POI_ORDER]. */
    fun categories(pois: List<TerminalPlace>): List<TerminalKind> {
        val present = pois.map { it.kind }.toSet()
        return TerminalKind.POI_ORDER.filter { it in present }
    }

    /** Walking distance hint in metres, rounded to 10 m (straight line, so "about"). */
    fun metresBetween(a: GeoPoint, b: GeoPoint): Int = ((GreatCircle.distanceKm(a, b) * 1000) / 10).toInt() * 10

    private val PREFIX_WORDS = Regex("\\b(GATE|GATES|BELT|BELTS|CAROUSEL|NO|NUMBER)\\b\\.?")
    private val LEADING_ZEROS = Regex("^0+(?=\\d)")
}
