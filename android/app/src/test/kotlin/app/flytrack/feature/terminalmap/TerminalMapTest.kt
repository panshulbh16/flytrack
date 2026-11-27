package app.flytrack.feature.terminalmap

import app.flytrack.core.estimate.GeoPoint
import app.flytrack.testing.Fixtures
import app.flytrack.testing.MainDispatcherRule
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TerminalMapParserTest {
    private val data = TerminalMapParser.parse(Fixtures.read("p4/terminal_map_test.geojson"))

    @Test
    fun readsHeaderAndSplitsFeatures() {
        assertEquals("DEL", data.iata)
        assertEquals(TerminalMapContract.ATTRIBUTION, data.attribution)
        assertEquals("2026-11-26T12:00:00Z", data.generatedAt)
        assertEquals(listOf("42B", "7", null), data.gates.map { it.ref })
        assertEquals(5, data.pois.size)
        assertEquals(listOf("way/4"), data.beltAreas.map { it.id })
        assertEquals(13, data.featureCount)
        assertEquals("1", data.gates.first().level)
    }

    @Test
    fun unknownKindsAreIgnoredAndBoundsSkipThem() {
        val bounds = data.bounds!!
        assertEquals(28.55, bounds.south, 1e-9)
        assertEquals(28.575, bounds.north, 1e-9)
        assertEquals(77.08, bounds.west, 1e-9)
        assertEquals(77.12, bounds.east, 1e-9)
        val terminal = data.terminalBounds!!
        assertEquals(77.085, terminal.west, 1e-9)
        assertEquals(28.559, terminal.north, 1e-9)
    }

    @Test
    fun polygonCentroidIgnoresTheClosingPoint() {
        val belt = data.beltAreas.single()
        assertEquals(28.5565, belt.point.lat, 1e-9)
        assertEquals(77.0865, belt.point.lon, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsANonCollection() {
        TerminalMapParser.parse("""{"type":"Feature"}""")
    }

    @Test
    fun iataHintIsUsedWhenTheFileHasNone() {
        val parsed = TerminalMapParser.parse("""{"type":"FeatureCollection","features":[]}""", "bom")
        assertEquals("BOM", parsed.iata)
        assertNull(parsed.bounds)
        assertEquals(TerminalMapContract.ATTRIBUTION, parsed.attribution)
    }
}

class TerminalMapLogicTest {
    private val data = TerminalMapParser.parse(Fixtures.read("p4/terminal_map_test.geojson"))
    private val labels: (TerminalKind) -> String = { it.wire.replace('_', ' ').replaceFirstChar(Char::uppercase) }

    @Test
    fun normalisesGateAndBeltRefs() {
        assertEquals("42B", TerminalMapLogic.normalizeRef("Gate 42B"))
        assertEquals("42B", TerminalMapLogic.normalizeRef(" 42 b "))
        assertEquals("42B", TerminalMapLogic.normalizeRef("042B"))
        assertEquals("7", TerminalMapLogic.normalizeRef("Gate No. 07"))
        assertEquals("5", TerminalMapLogic.normalizeRef("Belt 5"))
        assertEquals("0", TerminalMapLogic.normalizeRef("0"))
        assertEquals("A12", TerminalMapLogic.normalizeRef("A-12"))
        assertNull(TerminalMapLogic.normalizeRef("Gate"))
        assertNull(TerminalMapLogic.normalizeRef(null))
    }

    @Test
    fun findsTheUsersGate() {
        assertEquals("node/10", TerminalMapLogic.findGate(data.gates, "42b")?.id)
        assertEquals("node/11", TerminalMapLogic.findGate(data.gates, "07")?.id)
        // Mapped by name only.
        assertEquals("node/12", TerminalMapLogic.findGate(data.gates, "12")?.id)
        assertNull(TerminalMapLogic.findGate(data.gates, "99"))
        assertNull(TerminalMapLogic.findGate(data.gates, null))
    }

    @Test
    fun findsTheBelt() {
        assertEquals("way/4", TerminalMapLogic.findBelt(data.beltAreas, "Belt 5")?.id)
        assertNull(TerminalMapLogic.findBelt(data.beltAreas, "6"))
        val unnamed = listOf(data.beltAreas.single().copy(ref = null, name = null))
        // A single unnamed belt hall is the place to go, whatever the belt number.
        assertEquals("way/4", TerminalMapLogic.findBelt(unnamed, "6")?.id)
        assertNull(TerminalMapLogic.findBelt(unnamed, null))
    }

    @Test
    fun poiListByCategoryOrderThenName() {
        val all = TerminalMapLogic.filterPois(data.pois, "", null, labels)
        assertEquals(listOf("Burger Singh", "Chaayos", null, "Encalm Lounge", null), all.map { it.name })
        assertEquals(TerminalKind.TOILETS, all[2].kind)
        assertEquals(TerminalKind.PRAYER_ROOM, all[4].kind)
    }

    @Test
    fun searchMatchesNamesAndCategoryWords() {
        assertEquals(listOf("Chaayos"), TerminalMapLogic.filterPois(data.pois, "chaa", null, labels).map { it.name })
        assertEquals(listOf(TerminalKind.TOILETS), TerminalMapLogic.filterPois(data.pois, "toil", null, labels).map { it.kind })
        assertEquals(listOf(TerminalKind.PRAYER_ROOM), TerminalMapLogic.filterPois(data.pois, "prayer", null, labels).map { it.kind })
        assertEquals(2, TerminalMapLogic.filterPois(data.pois, "", TerminalKind.FOOD, labels).size)
        assertTrue(TerminalMapLogic.filterPois(data.pois, "pizza", null, labels).isEmpty())
        // The localised word counts too.
        val hindi: (TerminalKind) -> String = { if (it == TerminalKind.FOOD) "खाना" else it.wire }
        assertEquals(2, TerminalMapLogic.filterPois(data.pois, "खाना", null, hindi).size)
    }

    @Test
    fun nearestToTheGateFirstWhenTheGateIsKnown() {
        val gate = TerminalMapLogic.findGate(data.gates, "42B")!!
        val near = TerminalMapLogic.filterPois(data.pois, "", null, labels, gate.point)
        assertEquals("Chaayos", near.first().name)
        assertTrue(TerminalMapLogic.metresBetween(gate.point, near.first().point) in 10..40)
    }

    @Test
    fun categoriesPresentInOrder() {
        assertEquals(
            listOf(TerminalKind.FOOD, TerminalKind.TOILETS, TerminalKind.LOUNGE, TerminalKind.PRAYER_ROOM),
            TerminalMapLogic.categories(data.pois),
        )
    }
}

class TerminalMapStyleTest {
    private fun layerIds(json: String) =
        Json.parseToJsonElement(json).jsonObject.getValue("layers").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }

    @Test
    fun readsTheBundledAssetAndStaysOffline() {
        val style = Json.parseToJsonElement(TerminalMapStyle.styleJson("del", TerminalPalette.Light, null, null)).jsonObject
        val data = style.getValue("sources").jsonObject.getValue(TerminalMapStyle.SOURCE).jsonObject.getValue("data").jsonPrimitive.content
        assertEquals("asset://terminal-maps/DEL.geojson", data)
        assertFalse("glyphs" in style || "sprite" in style)
    }

    @Test
    fun gateHighlightOnlyWhenTheGateIsOnTheMap() {
        assertFalse(TerminalMapStyle.LAYER_MY_GATE in layerIds(TerminalMapStyle.styleJson("DEL", TerminalPalette.Light, null, null)))
        val json = TerminalMapStyle.styleJson("DEL", TerminalPalette.Dark, "42B", "way/4")
        val ids = layerIds(json)
        assertTrue(ids.containsAll(listOf(TerminalMapStyle.LAYER_MY_GATE_HALO, TerminalMapStyle.LAYER_MY_GATE, TerminalMapStyle.LAYER_MY_GATE_LABEL)))
        // The user's gate is drawn above the other gates.
        assertTrue(ids.indexOf(TerminalMapStyle.LAYER_MY_GATE) > ids.indexOf(TerminalMapStyle.LAYER_GATES))
        assertTrue(json.contains("\"tm-gate:42B\""))
        assertTrue(json.contains("\"way/4\""))
        assertTrue(json.contains(TerminalPalette.Dark.highlight))
    }

    @Test
    fun drawingOrderGroundToGates() {
        val ids = layerIds(TerminalMapStyle.styleJson("DEL", TerminalPalette.Light, null, null))
        val order = listOf(
            TerminalMapStyle.LAYER_BACKGROUND, TerminalMapStyle.LAYER_APRON, TerminalMapStyle.LAYER_RUNWAY,
            TerminalMapStyle.LAYER_TAXIWAY, TerminalMapStyle.LAYER_TERMINAL, TerminalMapStyle.LAYER_GATES, TerminalMapStyle.LAYER_GATE_LABELS,
        ).map(ids::indexOf)
        assertTrue(order.none { it < 0 })
        assertEquals(order.sorted(), order)
    }

    @Test
    fun selectedPoint() {
        val json = TerminalMapStyle.selectedGeoJson(GeoPoint(28.5, 77.1))
        assertTrue(json.contains("[77.1,28.5]"))
        assertTrue(TerminalMapStyle.selectedGeoJson(null).contains("\"features\":[]"))
    }
}

private class FakeSource(val files: Map<String, String>) : TerminalMapSource {
    var reads = 0
    override fun availableAirports(): Set<String> = files.keys
    override fun read(iata: String): String? {
        reads++
        return files[iata.uppercase()]
    }
}

class TerminalMapRepositoryTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun loadsParsesOnceAndReportsMissingAndBrokenFiles() = runTest {
        val source = FakeSource(mapOf("DEL" to Fixtures.read("p4/terminal_map_test.geojson"), "BOM" to "{ not json"))
        val repo = TerminalMapRepository(source, UnconfinedTestDispatcher(testScheduler))
        assertTrue(repo.load("del") is TerminalMapLoad.Ready)
        assertTrue(repo.load("DEL") is TerminalMapLoad.Ready)
        assertEquals(1, source.reads)
        assertEquals(TerminalMapLoad.Broken, repo.load("BOM"))
        assertEquals(TerminalMapLoad.NotAvailable, repo.load("JFK"))
        assertTrue(repo.isAvailable("del"))
        assertFalse(repo.isAvailable("BLR"))
        // Planned airports get a button even before their file ships; others do not.
        assertTrue(repo.isOffered("BLR"))
        assertFalse(repo.isOffered("JFK"))
    }

    @Test
    fun viewModelMatchesGateAndBeltAndFilters() = runTest {
        val source = FakeSource(mapOf("DEL" to Fixtures.read("p4/terminal_map_test.geojson")))
        val vm = TerminalMapViewModel(TerminalMapRepository(source, UnconfinedTestDispatcher(testScheduler)))
        vm.start("del", "42B", "Belt 5")
        val content = vm.uiState.value.content as TerminalMapContent.Ready
        assertEquals("node/10", content.myGate?.id)
        assertEquals("way/4", content.myBelt?.id)
        vm.setCategory(TerminalKind.FOOD)
        assertEquals(TerminalKind.FOOD, vm.uiState.value.category)
        vm.setCategory(TerminalKind.FOOD)
        assertNull(vm.uiState.value.category)
        vm.select("node/20")
        assertEquals("node/20", vm.uiState.value.selectedId)

        vm.start("JFK", null, null)
        assertEquals(TerminalMapContent.NotAvailable, vm.uiState.value.content)
    }

    @Test
    fun gateNotOnTheMapStaysKnownToTheScreen() = runTest {
        val source = FakeSource(mapOf("DEL" to Fixtures.read("p4/terminal_map_test.geojson")))
        val vm = TerminalMapViewModel(TerminalMapRepository(source, UnconfinedTestDispatcher(testScheduler)))
        vm.start("DEL", "99", null)
        val content = vm.uiState.value.content as TerminalMapContent.Ready
        assertNull(content.myGate)
        assertEquals("99", vm.uiState.value.gate)
    }
}
