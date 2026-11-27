package app.flytrack.feature.radar

import app.flytrack.core.estimate.GeoPoint
import app.flytrack.data.remote.BackendException
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.FakeClock
import app.flytrack.testing.Fixtures
import app.flytrack.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

private class FakeRadarRepository : RadarRepository {
    val queries = mutableListOf<RadarQuery>()
    val answers = ArrayDeque<RadarResult>()
    var fallback: RadarResult = RadarResult.Ok(RadarParser.parse(Fixtures.read("p4/radar_proximity.json")))

    override suspend fun nearby(query: RadarQuery): RadarResult {
        queries += query
        return answers.removeFirstOrNull() ?: fallback
    }
}

private class FakeLocation(var permission: Boolean = false, var result: LocationResult = LocationResult.Found(GeoPoint(28.6139, 77.2090))) :
    CoarseLocationProvider {
    var calls = 0
    override fun hasPermission(): Boolean = permission
    override suspend fun current(): LocationResult {
        calls++
        return result
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RadarViewModelTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    private val repo = FakeRadarRepository()
    private val location = FakeLocation()

    private fun vm() = RadarViewModel(repo, location, FakeClock())

    @Test
    fun groundRadarRefreshesEveryTenSecondsWhileVisible() = runTest(main.dispatcher) {
        val vm = vm()
        vm.start(RadarTarget.Airport("del"))
        assertEquals(RadarPhase.LOADING, vm.uiState.value.phase)
        vm.setVisible(true)
        runCurrent()
        assertEquals(listOf<RadarQuery>(RadarQuery.Ground("DEL")), repo.queries)
        assertEquals(RadarPhase.SHOWING, vm.uiState.value.phase)
        assertEquals(4, vm.uiState.value.rows.size)
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(1, repo.queries.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, repo.queries.size)
        // Hidden: no more requests.
        vm.setVisible(false)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, repo.queries.size)
        // Back: asks at once.
        vm.setVisible(true)
        runCurrent()
        assertEquals(3, repo.queries.size)
        vm.setVisible(false)
    }

    @Test
    fun nothingIsAskedUntilVisible() = runTest(main.dispatcher) {
        val vm = vm()
        vm.start(RadarTarget.Airport("BOM"))
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(repo.queries.isEmpty())
    }

    @Test
    fun nearMeWithoutPermissionWaitsForTheTap() = runTest(main.dispatcher) {
        val vm = vm()
        vm.start(RadarTarget.NearMe)
        vm.setVisible(true)
        runCurrent()
        assertEquals(RadarPhase.NEEDS_LOCATION, vm.uiState.value.phase)
        assertEquals(0, location.calls)
        assertTrue(repo.queries.isEmpty())

        location.permission = true
        vm.onLocationPermission(granted = true)
        runCurrent()
        // Rounded to 0.05 degrees before it reaches the repository.
        assertEquals(listOf<RadarQuery>(RadarQuery.Proximity(28.6, 77.2)), repo.queries)
        assertEquals(RadarPhase.SHOWING, vm.uiState.value.phase)
        vm.setVisible(false)
    }

    @Test
    fun nearMeWithPermissionLocatesAtOnce() = runTest(main.dispatcher) {
        location.permission = true
        val vm = vm()
        vm.start(RadarTarget.NearMe)
        vm.setVisible(true)
        runCurrent()
        assertEquals(1, location.calls)
        assertEquals(1, repo.queries.size)
        vm.setVisible(false)
    }

    @Test
    fun deniedAndLocationOff() = runTest(main.dispatcher) {
        val vm = vm()
        vm.start(RadarTarget.NearMe)
        vm.onLocationPermission(granted = false)
        assertEquals(RadarPhase.LOCATION_DENIED, vm.uiState.value.phase)
        location.permission = true
        location.result = LocationResult.Disabled
        vm.onLocationPermission(granted = true)
        runCurrent()
        assertEquals(RadarPhase.LOCATION_OFF, vm.uiState.value.phase)
        location.result = LocationResult.Unavailable
        vm.retry()
        runCurrent()
        assertEquals(RadarPhase.LOCATION_UNAVAILABLE, vm.uiState.value.phase)
        assertTrue(repo.queries.isEmpty())
    }

    @Test
    fun comingSoonStopsAsking() = runTest(main.dispatcher) {
        repo.fallback = RadarResult.ComingSoon
        val vm = vm()
        vm.start(RadarTarget.Around(GeoPoint(19.09, 72.87)))
        vm.setVisible(true)
        runCurrent()
        assertEquals(RadarPhase.COMING_SOON, vm.uiState.value.phase)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, repo.queries.size)
        assertEquals(RadarQuery.Proximity(19.1, 72.85), repo.queries.single())
    }

    @Test
    fun feedErrorBeforeDataIsTheFriendlyStateAndRetries() = runTest(main.dispatcher) {
        repo.answers += RadarResult.FeedError
        val vm = vm()
        vm.start(RadarTarget.Airport("DEL"))
        vm.setVisible(true)
        runCurrent()
        assertEquals(RadarPhase.COMING_SOON, vm.uiState.value.phase)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(RadarPhase.SHOWING, vm.uiState.value.phase)
        vm.setVisible(false)
    }

    @Test
    fun problemsAfterDataKeepThePlanesWithANotice() = runTest(main.dispatcher) {
        val vm = vm()
        vm.start(RadarTarget.Airport("DEL"))
        vm.setVisible(true)
        runCurrent()
        repo.answers += RadarResult.Offline
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(RadarPhase.SHOWING, vm.uiState.value.phase)
        assertEquals(RadarNotice.STALE_OFFLINE, vm.uiState.value.notice)
        assertEquals(4, vm.uiState.value.rows.size)

        repo.answers += RadarResult.RateLimited
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(RadarNotice.SLOWED_DOWN, vm.uiState.value.notice)
        val asked = repo.queries.size
        advanceTimeBy(59_000)
        runCurrent()
        assertEquals(asked, repo.queries.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(asked + 1, repo.queries.size)
        assertNull(vm.uiState.value.notice)
        vm.setVisible(false)
    }

    @Test
    fun selectionTogglesAndSurvivesRefreshes() = runTest(main.dispatcher) {
        val vm = vm()
        vm.start(RadarTarget.Airport("DEL"))
        vm.setVisible(true)
        runCurrent()
        vm.select("800c1b")
        assertEquals("6E 2341", vm.uiState.value.selected?.label)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("800c1b", vm.uiState.value.selectedKey)
        vm.select("800c1b")
        assertNull(vm.uiState.value.selectedKey)
        vm.setVisible(false)
    }

    @Test
    fun proRequiredAndUnknownAirport() = runTest(main.dispatcher) {
        repo.fallback = RadarResult.ProRequired
        val vm = vm()
        vm.start(RadarTarget.Airport("DEL"))
        vm.setVisible(true)
        runCurrent()
        assertEquals(RadarPhase.PRO_REQUIRED, vm.uiState.value.phase)
        repo.fallback = RadarResult.UnknownAirport
        vm.start(RadarTarget.Airport("XXX"))
        runCurrent()
        assertEquals(RadarPhase.UNKNOWN_AIRPORT, vm.uiState.value.phase)
        vm.setVisible(false)
    }
}

class DefaultRadarRepositoryTest {
    private class FakeApi(var answer: () -> String) : RadarApi {
        val queries = mutableListOf<RadarQuery>()
        override suspend fun fetch(query: RadarQuery): String {
            queries += query
            return answer()
        }
    }

    @Test
    fun okAndErrors() = runTest {
        val api = FakeApi { Fixtures.read("p4/radar_proximity.json") }
        val repo = DefaultRadarRepository(api, FakeAuthRepository(), UnconfinedTestDispatcher(testScheduler))
        assertTrue(repo.nearby(RadarQuery.Ground("DEL")) is RadarResult.Ok)

        api.answer = { throw BackendException.Http(503, "feed_unlicensed", null) }
        assertEquals(RadarResult.ComingSoon, repo.nearby(RadarQuery.Ground("DEL")))
        api.answer = { throw BackendException.Http(402, "pro_required", null) }
        assertEquals(RadarResult.ProRequired, repo.nearby(RadarQuery.Ground("DEL")))
        api.answer = { throw BackendException.Network(IOException("down")) }
        assertEquals(RadarResult.Offline, repo.nearby(RadarQuery.Ground("DEL")))
        api.answer = { throw BackendException.NotConfigured() }
        assertEquals(RadarResult.NotConfigured, repo.nearby(RadarQuery.Ground("DEL")))
        api.answer = { "not json" }
        assertTrue(repo.nearby(RadarQuery.Ground("DEL")) is RadarResult.Failed)
    }

    @Test
    fun notSignedInBackendIsNotConfigured() = runTest {
        val api = FakeApi { error("must not be called") }
        val repo = DefaultRadarRepository(api, FakeAuthRepository.notConfigured(), UnconfinedTestDispatcher(testScheduler))
        assertEquals(RadarResult.NotConfigured, repo.nearby(RadarQuery.Ground("DEL")))
        assertTrue(api.queries.isEmpty())
    }
}
