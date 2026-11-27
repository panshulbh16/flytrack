package app.flytrack.feature.terminalmap

import app.flytrack.core.estimate.GeoPoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
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

/** Terminal map colours, from docs/design-brief.md tokens (light and dark). */
data class TerminalPalette(
    val ground: String,
    val apron: String,
    val runway: String,
    val taxiway: String,
    val terminalFill: String,
    val terminalOutline: String,
    val beltFill: String,
    val jetBridge: String,
    val gateFill: String,
    val gateRing: String,
    val highlight: String,
    val poiFood: String,
    val poiToilets: String,
    val poiLounge: String,
    val poiAtm: String,
    val poiPrayer: String,
    val poiOther: String,
) {
    companion object {
        val Light = TerminalPalette(
            ground = "#EAEEE1", // surfaceContainerHigh
            apron = "#D6DCCF", // outlineVariant
            runway = "#768775", // outline
            taxiway = "#C2D0C1", // map graticule
            terminalFill = "#FBFAF5", // raised rows
            terminalOutline = "#244735", // ink / primary
            beltFill = "#D9DFC8", // primaryContainer
            jetBridge = "#768775",
            gateFill = "#F6F5EF", // paper
            gateRing = "#C86842", // brand terracotta (decoration)
            highlight = "#C86842",
            poiFood = "#8D542D", // delayed text tone
            poiToilets = "#5A6C5A", // muted
            poiLounge = "#244735",
            poiAtm = "#4C7B41", // on-time
            poiPrayer = "#A34E2E", // accent text
            poiOther = "#768775",
        )
        val Dark = TerminalPalette(
            ground = "#1B3329",
            apron = "#2C473D",
            runway = "#7A927E",
            taxiway = "#4C6450",
            terminalFill = "#1F3A2F",
            terminalOutline = "#A0B99F",
            beltFill = "#35533E",
            jetBridge = "#7A927E",
            gateFill = "#F6F4E7",
            gateRing = "#DF754E",
            highlight = "#DF754E",
            poiFood = "#E8C98A",
            poiToilets = "#C0CDBB",
            poiLounge = "#A0B99F",
            poiAtm = "#A9CFA0",
            poiPrayer = "#E88A62",
            poiOther = "#7A927E",
        )

        fun of(dark: Boolean): TerminalPalette = if (dark) Dark else Light
    }
}

/**
 * The terminal map's MapLibre style: one GeoJSON source read straight from the bundled asset, drawn with
 * fill, line, circle and symbol layers. Offline: no tiles, no glyphs, no sprite. Gate numbers are
 * bitmap images named by [gateImageId] (added by the view), because there are no glyphs for `text-field`.
 *
 * @param highlightGateRef the `ref` of the user's gate exactly as in the file (see [TerminalMapLogic.findGate])
 * @param highlightBeltId the feature id of the arrival belt area
 */
object TerminalMapStyle {
    const val SOURCE = "terminal"
    const val SOURCE_SELECTED = "terminal-selected"

    const val LAYER_BACKGROUND = "tm-background"
    const val LAYER_APRON = "tm-apron"
    const val LAYER_RUNWAY = "tm-runway"
    const val LAYER_TAXIWAY = "tm-taxiway"
    const val LAYER_TERMINAL = "tm-terminal"
    const val LAYER_TERMINAL_OUTLINE = "tm-terminal-outline"
    const val LAYER_BELT = "tm-belt"
    const val LAYER_BELT_HIGHLIGHT = "tm-belt-highlight"
    const val LAYER_BELT_POINT_HIGHLIGHT = "tm-belt-point-highlight"
    const val LAYER_JET_BRIDGE = "tm-jet-bridge"
    const val LAYER_PARKING = "tm-parking"
    const val LAYER_GATES = "tm-gates"
    const val LAYER_GATE_LABELS = "tm-gate-labels"
    const val LAYER_POIS = "tm-pois"
    const val LAYER_MY_GATE_HALO = "tm-my-gate-halo"
    const val LAYER_MY_GATE = "tm-my-gate"
    const val LAYER_MY_GATE_LABEL = "tm-my-gate-label"
    const val LAYER_SELECTED = "tm-selected"

    const val PROP_KIND = "kind"
    const val PROP_REF = "ref"

    private const val GATE_IMAGE_PREFIX = "tm-gate:"

    fun gateImageId(ref: String): String = GATE_IMAGE_PREFIX + ref

    fun styleJson(iata: String, palette: TerminalPalette, highlightGateRef: String?, highlightBeltId: String?): String =
        style(TerminalMapContract.assetUrl(iata), palette, highlightGateRef, highlightBeltId).toString()

    fun style(dataUrl: String, palette: TerminalPalette, highlightGateRef: String?, highlightBeltId: String?): JsonObject = buildJsonObject {
        put("version", 8)
        put("name", "HeyGate terminal map (offline)")
        putJsonObject("sources") {
            putJsonObject(SOURCE) {
                put("type", "geojson")
                put("data", dataUrl)
            }
            putJsonObject(SOURCE_SELECTED) {
                put("type", "geojson")
                put("data", emptyCollection())
            }
        }
        putJsonArray("layers") {
            add(buildJsonObject {
                put("id", LAYER_BACKGROUND)
                put("type", "background")
                putJsonObject("paint") { put("background-color", palette.ground) }
            })
            add(fill(LAYER_APRON, kindIs("apron")) {
                put("fill-color", palette.apron)
            })
            add(line(LAYER_RUNWAY, kindIs("runway")) {
                put("line-color", palette.runway)
                put("line-width", zoomWidth(14.0 to 6.0, 18.0 to 40.0))
            })
            add(line(LAYER_TAXIWAY, kindIs("taxiway")) {
                put("line-color", palette.taxiway)
                put("line-width", zoomWidth(14.0 to 1.5, 18.0 to 12.0))
            })
            add(fill(LAYER_TERMINAL, kindIs("terminal")) {
                put("fill-color", palette.terminalFill)
            })
            add(line(LAYER_TERMINAL_OUTLINE, kindIs("terminal")) {
                put("line-color", palette.terminalOutline)
                put("line-width", 1.2)
            })
            add(fill(LAYER_BELT, kindIs("baggage_claim")) {
                put("fill-color", palette.beltFill)
            })
            add(fill(LAYER_BELT_HIGHLIGHT, idIs(highlightBeltId)) {
                put("fill-color", palette.highlight)
                put("fill-opacity", 0.35)
                put("fill-outline-color", palette.highlight)
            })
            // A belt hall mapped as a point only.
            add(circle(LAYER_BELT_POINT_HIGHLIGHT, idIs(highlightBeltId)) {
                put("circle-radius", 12.0)
                put("circle-color", palette.highlight)
                put("circle-opacity", 0.35)
            })
            add(line(LAYER_JET_BRIDGE, kindIs("jet_bridge")) {
                put("line-color", palette.jetBridge)
                put("line-width", 2.0)
            })
            add(circle(LAYER_PARKING, kindIs("parking_position"), minZoom = 16.0) {
                put("circle-radius", 1.6)
                put("circle-color", palette.runway)
            })
            add(circle(LAYER_POIS, poiFilter(), minZoom = 15.5) {
                put("circle-radius", 3.5)
                put("circle-color", poiColor(palette))
                put("circle-stroke-color", palette.gateFill)
                put("circle-stroke-width", 1.0)
            })
            add(circle(LAYER_GATES, kindIs("gate")) {
                put("circle-radius", 3.5)
                put("circle-color", palette.gateFill)
                put("circle-stroke-color", palette.gateRing)
                put("circle-stroke-width", 1.5)
            })
            add(buildJsonObject {
                put("id", LAYER_GATE_LABELS)
                put("type", "symbol")
                put("source", SOURCE)
                put("minzoom", 15.5)
                put("filter", all(kindIs("gate"), hasRef()))
                putJsonObject("layout") {
                    put("icon-image", gateImageExpression())
                    put("icon-anchor", "left")
                    putJsonArray("icon-offset") {
                        add(6)
                        add(0)
                    }
                    put("icon-optional", true)
                }
            })
            if (highlightGateRef != null) {
                val mine = all(kindIs("gate"), refIs(highlightGateRef))
                add(circle(LAYER_MY_GATE_HALO, mine) {
                    put("circle-radius", 14.0)
                    put("circle-color", palette.highlight)
                    put("circle-opacity", 0.22)
                })
                add(circle(LAYER_MY_GATE, mine) {
                    put("circle-radius", 6.5)
                    put("circle-color", palette.highlight)
                    put("circle-stroke-color", palette.gateFill)
                    put("circle-stroke-width", 2.0)
                })
                add(buildJsonObject {
                    put("id", LAYER_MY_GATE_LABEL)
                    put("type", "symbol")
                    put("source", SOURCE)
                    put("filter", mine)
                    putJsonObject("layout") {
                        put("icon-image", gateImageId(highlightGateRef))
                        put("icon-anchor", "left")
                        putJsonArray("icon-offset") {
                            add(10)
                            add(0)
                        }
                        // The user's gate label always shows, at every zoom.
                        put("icon-allow-overlap", true)
                        put("icon-ignore-placement", true)
                    }
                })
            }
            add(buildJsonObject {
                put("id", LAYER_SELECTED)
                put("type", "circle")
                put("source", SOURCE_SELECTED)
                putJsonObject("paint") {
                    put("circle-radius", 9.0)
                    put("circle-color", "rgba(0,0,0,0)")
                    put("circle-stroke-color", palette.highlight)
                    put("circle-stroke-width", 2.5)
                }
            })
        }
    }

    /** The selected POI (tapped in the list) as one point, or nothing. */
    fun selectedGeoJson(point: GeoPoint?): String = if (point == null) {
        emptyCollection().toString()
    } else {
        buildJsonObject {
            put("type", "FeatureCollection")
            putJsonArray("features") {
                add(buildJsonObject {
                    put("type", "Feature")
                    putJsonObject("properties") {}
                    putJsonObject("geometry") {
                        put("type", "Point")
                        putJsonArray("coordinates") {
                            add(point.lon)
                            add(point.lat)
                        }
                    }
                })
            }
        }.toString()
    }

    // ---- expressions -----------------------------------------------------------------------------------

    private fun expr(vararg parts: Any): JsonArray = buildJsonArray {
        for (p in parts) addAny(p)
    }

    private fun JsonArrayBuilder.addAny(p: Any) {
        when (p) {
            is JsonElement -> add(p)
            is String -> add(p)
            is Number -> add(JsonPrimitive(p))
            is Boolean -> add(p)
            else -> error("unsupported expression part $p")
        }
    }

    private fun kindIs(kind: String) = expr("==", expr("get", PROP_KIND), kind)
    private fun refIs(ref: String) = expr("==", expr("get", PROP_REF), ref)
    private fun hasRef() = expr("has", PROP_REF)

    /** Matches the feature id ("way/123"); matches nothing when [id] is null. */
    private fun idIs(id: String?) = if (id == null) expr("==", 1, 0) else expr("==", expr("id"), id)

    private fun all(vararg filters: JsonArray) = buildJsonArray {
        add("all")
        filters.forEach { add(it) }
    }

    private fun poiFilter() = expr("in", expr("get", PROP_KIND), expr("literal", buildJsonArray { TerminalKind.POI_ORDER.forEach { add(it.wire) } }))

    private fun poiColor(p: TerminalPalette) = buildJsonArray {
        add("match")
        add(expr("get", PROP_KIND))
        add("food"); add(p.poiFood)
        add("toilets"); add(p.poiToilets)
        add("lounge"); add(p.poiLounge)
        add("atm"); add(p.poiAtm)
        add("prayer_room"); add(p.poiPrayer)
        add(p.poiOther)
    }

    private fun gateImageExpression() = expr("concat", GATE_IMAGE_PREFIX, expr("get", PROP_REF))

    private fun zoomWidth(vararg stops: Pair<Double, Double>) = buildJsonArray {
        add("interpolate")
        addJsonArray { add("exponential"); add(1.6) }
        add(expr("zoom"))
        for ((z, w) in stops) {
            add(z)
            add(w)
        }
    }

    private fun fill(id: String, filter: JsonArray, paint: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("id", id)
        put("type", "fill")
        put("source", SOURCE)
        // Fill layers draw only (multi)polygons, so the kind is the whole filter.
        put("filter", filter)
        putJsonObject("paint", paint)
    }

    private fun line(id: String, filter: JsonArray, paint: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("id", id)
        put("type", "line")
        put("source", SOURCE)
        put("filter", filter)
        putJsonObject("layout") {
            put("line-cap", "round")
            put("line-join", "round")
        }
        putJsonObject("paint", paint)
    }

    private fun circle(id: String, filter: JsonArray, minZoom: Double? = null, paint: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("id", id)
        put("type", "circle")
        put("source", SOURCE)
        minZoom?.let { put("minzoom", it) }
        put("filter", all(filter, expr("==", expr("geometry-type"), "Point")))
        putJsonObject("paint", paint)
    }

    private fun emptyCollection() = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", JsonArray(emptyList()))
    }
}
