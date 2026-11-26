package app.flytrack.feature.map

import app.flytrack.core.estimate.GeoPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FlightMapStyleTest {
    private val style = Json.parseToJsonElement(FlightMapStyle.styleJson(MapPalette.Light)).jsonObject
    private val layers = style.getValue("layers").jsonArray.map { it.jsonObject }

    private fun layer(id: String): JsonObject = layers.first { it.getValue("id").jsonPrimitive.content == id }
    private fun JsonObject.paint(key: String) = getValue("paint").jsonObject.getValue(key).jsonPrimitive.content

    @Test
    fun `the style never reaches the network`() {
        val text = FlightMapStyle.styleJson(MapPalette.Dark)
        assertFalse(text.contains("http://"))
        assertFalse(text.contains("https://"))
        assertFalse(style.containsKey("glyphs"))
        assertFalse(style.containsKey("sprite"))
        val sources = style.getValue("sources").jsonObject.values.map { it.jsonObject }
        assertTrue(sources.all { it.getValue("type").jsonPrimitive.content == "geojson" })
        assertEquals(
            "asset://map/land-110m.geojson",
            style.getValue("sources").jsonObject.getValue(FlightMapStyle.SOURCE_LAND).jsonObject.getValue("data").jsonPrimitive.content,
        )
    }

    @Test
    fun `layers are drawn ocean, graticule, land, route, airports, plane`() {
        assertEquals(8, style.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(
            listOf("ocean", "graticule", "land", "land-outline", "route-remaining", "route-flown", "airports", "plane"),
            layers.map { it.getValue("id").jsonPrimitive.content },
        )
        // Every layer's source exists.
        val sources = style.getValue("sources").jsonObject.keys
        layers.mapNotNull { it["source"]?.jsonPrimitive?.content }.forEach { assertTrue(it, it in sources) }
    }

    @Test
    fun `colours come from the design brief`() {
        assertEquals("#E2E9DF", layer("ocean").paint("background-color"))
        assertEquals("#3E6B56", layer("land").paint("fill-color"))
        assertEquals("#C2D0C1", layer("graticule").paint("line-color"))
        assertEquals("#DF754E", layer("route-flown").paint("line-color"))
        assertEquals("#DF754E", layer("route-remaining").paint("line-color"))
        assertEquals("#F6F5EF", layer("airports").paint("circle-color"))
        assertEquals("#DF754E", layer("airports").paint("circle-stroke-color"))

        val dark = Json.parseToJsonElement(FlightMapStyle.styleJson(MapPalette.Dark)).jsonObject
            .getValue("layers").jsonArray.map { it.jsonObject }
        assertEquals("#253E36", dark.first().getValue("paint").jsonObject.getValue("background-color").jsonPrimitive.content)
    }

    @Test
    fun `flown is solid, remaining is dashed, the plane rotates by its bearing`() {
        assertFalse(layer("route-flown").getValue("paint").jsonObject.containsKey("line-dasharray"))
        assertTrue(layer("route-remaining").getValue("paint").jsonObject.containsKey("line-dasharray"))
        val planeLayout = layer("plane").getValue("layout").jsonObject
        assertEquals(FlightMapStyle.PLANE_IMAGE, planeLayout.getValue("icon-image").jsonPrimitive.content)
        assertEquals(listOf("get", "bearing"), planeLayout.getValue("icon-rotate").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("map", planeLayout.getValue("icon-rotation-alignment").jsonPrimitive.content)
    }

    @Test
    fun `geojson helpers use lon-lat order`() {
        val line = Json.parseToJsonElement(FlightMapStyle.lineGeoJson(listOf(GeoPoint(28.5, 77.1), GeoPoint(19.0, 72.8)))).jsonObject
        val coords = line.getValue("features").jsonArray[0].jsonObject.getValue("geometry").jsonObject.getValue("coordinates").jsonArray
        assertEquals(77.1, coords[0].jsonArray[0].jsonPrimitive.double, 0.0)
        assertEquals(28.5, coords[0].jsonArray[1].jsonPrimitive.double, 0.0)

        val plane = Json.parseToJsonElement(FlightMapStyle.planeGeoJson(GeoPoint(23.0, 74.0), 203.5)).jsonObject
        val feature = plane.getValue("features").jsonArray.single().jsonObject
        assertEquals(203.5, feature.getValue("properties").jsonObject.getValue("bearing").jsonPrimitive.double, 0.0)

        val empty = Json.parseToJsonElement(FlightMapStyle.planeGeoJson(null, 0.0)).jsonObject
        assertTrue(empty.getValue("features").jsonArray.isEmpty())
        assertTrue(Json.parseToJsonElement(FlightMapStyle.lineGeoJson(listOf(GeoPoint(1.0, 1.0)))).jsonObject.getValue("features").jsonArray.isEmpty())
    }

    @Test
    fun `graticule has a meridian every 10 degrees and parallels to 80`() {
        val lines = FlightMapStyle.graticule().getValue("features").jsonArray.single().jsonObject
            .getValue("geometry").jsonObject.getValue("coordinates").jsonArray
        assertEquals(37 + 17, lines.size)
    }

    @Test
    fun `the bundled land asset is valid GeoJSON covering India`() {
        val file = listOf("src/main/assets/map/land-110m.geojson", "app/src/main/assets/map/land-110m.geojson")
            .map(::File).first { it.exists() }
        val land = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("FeatureCollection", land.getValue("type").jsonPrimitive.content)
        val geometry = land.getValue("features").jsonArray.single().jsonObject.getValue("geometry").jsonObject
        assertEquals("MultiPolygon", geometry.getValue("type").jsonPrimitive.content)
        val points = geometry.getValue("coordinates").jsonArray.flatMap { polygon ->
            polygon.jsonArray.flatMap { ring -> ring.jsonArray.map { it.jsonArray } }
        }
        assertTrue(points.size > 4000)
        // The west coast near Mumbai is on the outline.
        assertTrue(points.any { p ->
            val lon = p[0].jsonPrimitive.double
            val lat = p[1].jsonPrimitive.double
            lon in 72.0..74.0 && lat in 18.0..20.0
        })
    }
}
