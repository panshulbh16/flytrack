package app.flytrack.feature.tripit

import app.flytrack.data.remote.BackendException
import app.flytrack.feature.friends.FakeWave2Api
import app.flytrack.feature.friends.caller
import app.flytrack.testing.FakeBackendApi
import app.flytrack.testing.FakeFlightRepository
import app.flytrack.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TripItParserTest {
    @Test
    fun status() {
        val connected = TripItParser.status(Json.parseToJsonElement("""{"connected":true,"last_sync_at":"2026-11-27T09:00:00.5+00:00","last_error":"tripit_revoked"}"""))
        assertTrue(connected.connected)
        assertEquals(Instant.parse("2026-11-27T09:00:00.5Z"), connected.lastSyncAt)
        assertEquals(TripItLastError.REVOKED, connected.lastError)
        val off = TripItParser.status(Json.parseToJsonElement("""{"connected":false,"last_sync_at":null,"last_error":null}"""))
        assertFalse(off.connected)
        assertNull(off.lastSyncAt)
        assertFalse(TripItParser.status(JsonNull).connected)
    }

    @Test
    fun onlyOpensTripItAuthorizePages() {
        val url = "https://www.tripit.com/oauth/authorize?oauth_token=abc&oauth_callback=https%3A%2F%2Fx.supabase.co%2Ffunctions%2Fv1%2Ftripit-callback"
        assertEquals(url, TripItParser.authorizeUrl("""{"authorize_url":"$url"}"""))
        assertTrue(TripItParser.isTripItAuthorizeUrl("https://tripit.com/oauth/authorize?oauth_token=1"))
        assertFalse(TripItParser.isTripItAuthorizeUrl("http://www.tripit.com/oauth/authorize"))
        assertFalse(TripItParser.isTripItAuthorizeUrl("https://www.tripit.com.evil.example/oauth"))
        assertFalse(TripItParser.isTripItAuthorizeUrl("javascript:alert(1)"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnotherHost() {
        TripItParser.authorizeUrl("""{"authorize_url":"https://evil.example/oauth"}""")
    }

    @Test
    fun deepLinks() {
        assertEquals(TripItLink.Connected, TripItParser.link("heygate://tripit/connected"))
        assertEquals(TripItLink.Connected, TripItParser.link("HEYGATE://tripit/connected/"))
        assertEquals(TripItLink.Failed(TripItLinkError.DENIED), TripItParser.link("heygate://tripit/failed?error=denied"))
        assertEquals(TripItLink.Failed(TripItLinkError.EXPIRED), TripItParser.link("heygate://tripit/failed?x=1&error=expired"))
        assertEquals(TripItLink.Failed(TripItLinkError.TRIPIT_ERROR), TripItParser.link("heygate://tripit/failed?error=tripit_error"))
        assertEquals(TripItLink.Failed(TripItLinkError.NOT_CONFIGURED), TripItParser.link("heygate://tripit/failed?error=not_configured"))
        assertEquals(TripItLink.Failed(TripItLinkError.INTERNAL), TripItParser.link("heygate://tripit/failed"))
        assertNull(TripItParser.link("heygate://family/join?code=X"))
        assertNull(TripItParser.link("https://tripit.com/connected"))
        assertNull(TripItParser.link("heygate://tripit/other"))
        assertNull(TripItParser.link(null))
    }

    @Test
    fun callbackCodeDeepLinks() {
        val code = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde"
        assertEquals(43, code.length)
        assertEquals(TripItLink.Code(code), TripItParser.link("heygate://tripit/callback?code=$code"))
        assertEquals(TripItLink.Code(code), TripItParser.link("HEYGATE://TripIt/callback/?x=1&code=$code"))
        // missing, short, long or non-base64url codes are a failed connect, never a call to the server
        for (bad in listOf("", "?code=", "?code=abc", "?code=${code}x", "?code=${code.dropLast(1)}%2B", "?other=$code")) {
            assertEquals(bad, TripItLink.Failed(TripItLinkError.INTERNAL), TripItParser.link("heygate://tripit/callback$bad"))
        }
        assertNull(TripItParser.link("https://evil.example/tripit/callback?code=$code"))
        assertNull(TripItParser.link("heygate://family/callback?code=$code"))
        assertFalse(TripItLink.Code(code).toString().contains(code))
        assertEquals("""{"code":"$code"}""", TripItParser.completeBody(code))
    }

    @Test
    fun cooldownAndCompleteRefusals() {
        assertEquals(TripItResult.Cooldown(42), TripItParser.refused<Unit>("cooldown", "retry_after_s=42"))
        assertEquals(TripItResult.Cooldown(60), TripItParser.refused<Unit>("cooldown", null))
        assertEquals(TripItResult.Cooldown(60), TripItParser.refused<Unit>("cooldown", "retry_after_s=99999"))
        assertEquals(TripItResult.Cooldown(1), TripItParser.refused<Unit>("cooldown", "retry_after_s=0"))
        assertEquals(TripItResult.NotYourLink, TripItParser.refused<Unit>("not_your_link"))
        assertEquals(TripItResult.Expired, TripItParser.refused<Unit>("expired"))
        assertEquals(TripItResult.Denied, TripItParser.refused<Unit>("denied"))
    }

    @Test
    fun completeFailureMessages() {
        assertEquals(TripItMessage.NOT_YOUR_LINK, TripItViewModel.completeFailure(TripItResult.NotYourLink))
        assertEquals(TripItMessage.CONNECT_EXPIRED, TripItViewModel.completeFailure(TripItResult.Expired))
        assertEquals(TripItMessage.CONNECT_DENIED, TripItViewModel.completeFailure(TripItResult.Denied))
        assertEquals(TripItMessage.TRIPIT_DOWN, TripItViewModel.completeFailure(TripItResult.TripItDown))
        assertEquals(TripItMessage.OFFLINE, TripItViewModel.completeFailure(TripItResult.Offline))
        assertEquals(TripItMessage.CONNECT_FAILED, TripItViewModel.completeFailure(TripItResult.Failed("x")))
        assertEquals(TripItMessage.CONNECT_FAILED, TripItViewModel.completeFailure(TripItResult.ProRequired))
        assertNull(TripItViewModel.completeFailure(TripItResult.NotOffered))
    }

    @Test
    fun syncSummaryAndRefusals() {
        val summary = TripItParser.syncSummary("""{"segments":5,"added":2,"existing":2,"not_found":1,"skipped":0,"errors":0}""")
        assertEquals(TripItSyncSummary(5, 2, 2, 1, 0, 0), summary)
        assertEquals(TripItResult.ProRequired, TripItParser.refused<Unit>("pro_required"))
        assertEquals(TripItResult.NotConnected, TripItParser.refused<Unit>("not_connected"))
        assertEquals(TripItResult.Revoked, TripItParser.refused<Unit>("tripit_revoked"))
        assertEquals(TripItResult.TripItDown, TripItParser.refused<Unit>("tripit_error"))
        assertEquals(TripItResult.NotOffered, TripItParser.refused<Unit>("not_configured"))
        assertTrue(TripItParser.refused<Unit>(null) is TripItResult.Failed)
    }

    @Test
    fun deepLinkHolder() {
        val links = TripItLinkStore()
        assertFalse(links.offer("https://example.com"))
        assertTrue(links.offer("heygate://tripit/connected"))
        assertEquals(TripItLink.Connected, links.pending.value)
        assertEquals(TripItLink.Connected, links.consume())
        assertNull(links.pending.value)
    }
}

class SupabaseTripItRepositoryTest {
    @Test
    fun callsTheContractNames() = runTest {
        val wave2 = FakeWave2Api().apply { answers["tripit_status"] = Json.parseToJsonElement("""{"connected":true,"last_sync_at":null,"last_error":null}""") }
        val backend = FakeBackendApi().apply {
            functionHandlers["tripit-connect"] = { """{"authorize_url":"https://www.tripit.com/oauth/authorize?oauth_token=t"}""" }
            functionHandlers["tripit-sync"] = { """{"segments":1,"added":1,"existing":0,"not_found":0,"skipped":0,"errors":0}""" }
            functionHandlers["tripit-disconnect"] = { """{"ok":true}""" }
            functionHandlers["tripit-complete"] = { """{"connected":true}""" }
        }
        val repo = SupabaseTripItRepository(caller(wave2), backend)
        assertEquals(TripItResult.Ok(TripItStatus(true, null, null)), repo.status())
        assertEquals(TripItResult.Ok("https://www.tripit.com/oauth/authorize?oauth_token=t"), repo.connect())
        assertEquals(1, (repo.sync() as TripItResult.Ok).value.added)
        assertEquals(TripItResult.Ok(Unit), repo.disconnect())
        assertTrue(backend.calls.any { it.startsWith("invokeFunction(tripit-connect") })
        assertEquals(TripItResult.Ok(Unit), repo.complete(CODE))
        assertTrue(backend.calls.any { it.startsWith("invokeFunction(tripit-complete") && it.contains(CODE) })
    }

    @Test
    fun mapsCompleteAndCooldownErrors() = runTest {
        val backend = FakeBackendApi().apply {
            functionHandlers["tripit-complete"] = { throw BackendException.Http(403, "not_your_link", null) }
            functionHandlers["tripit-sync"] = { throw BackendException.Http(429, "cooldown", "retry_after_s=17") }
        }
        val repo = SupabaseTripItRepository(caller(FakeWave2Api()), backend)
        assertEquals(TripItResult.NotYourLink, repo.complete(CODE))
        assertEquals(TripItResult.Cooldown(17), repo.sync())
        backend.functionHandlers["tripit-complete"] = { throw BackendException.Http(410, "expired", null) }
        assertEquals(TripItResult.Expired, repo.complete(CODE))
    }

    @Test
    fun mapsFunctionErrors() = runTest {
        val backend = FakeBackendApi().apply {
            functionHandlers["tripit-sync"] = { throw BackendException.Http(409, "tripit_revoked", null) }
            functionHandlers["tripit-connect"] = { throw BackendException.Http(503, "not_configured", null) }
            functionHandlers["tripit-disconnect"] = { throw BackendException.Network(IOException("x")) }
        }
        val repo = SupabaseTripItRepository(caller(FakeWave2Api()), backend)
        assertEquals(TripItResult.Revoked, repo.sync())
        assertEquals(TripItResult.NotOffered, repo.connect())
        assertEquals(TripItResult.Offline, repo.disconnect())
    }
}

private const val CODE = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde"

private class FakeTripItRepository : TripItRepository {
    var complete: TripItResult<Unit> = TripItResult.Ok(Unit)
    val completed = mutableListOf<String>()
    var status: TripItResult<TripItStatus> = TripItResult.Ok(TripItStatus(false, null, null))
    var connect: TripItResult<String> = TripItResult.Ok("https://www.tripit.com/oauth/authorize?oauth_token=t")
    var sync: TripItResult<TripItSyncSummary> = TripItResult.Ok(TripItSyncSummary(added = 2))
    var disconnect: TripItResult<Unit> = TripItResult.Ok(Unit)
    var statusCalls = 0
    override suspend fun status(): TripItResult<TripItStatus> {
        statusCalls++
        return status
    }
    override suspend fun connect() = connect
    override suspend fun complete(code: String): TripItResult<Unit> {
        completed += code
        return complete
    }
    override suspend fun sync() = sync
    override suspend fun disconnect() = disconnect
}

@OptIn(ExperimentalCoroutinesApi::class)
class TripItViewModelTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    private val repo = FakeTripItRepository()
    private val flights = FakeFlightRepository()
    private val links = TripItLinkStore()

    @Test
    fun connectOpensTheAuthorizePageOnce() = runTest(main.dispatcher) {
        val vm = TripItViewModel(repo, flights, links)
        runCurrent()
        assertEquals(TripItRowState.Disconnected, vm.uiState.value.row)
        vm.connect()
        runCurrent()
        assertEquals("https://www.tripit.com/oauth/authorize?oauth_token=t", vm.uiState.value.openUrl)
        vm.authorizeOpened()
        assertNull(vm.uiState.value.openUrl)
    }

    @Test
    fun hiddenWithoutServerKeyAndPaywallOnProRequired() = runTest(main.dispatcher) {
        repo.status = TripItResult.NotOffered
        val vm = TripItViewModel(repo, flights, links)
        runCurrent()
        assertEquals(TripItRowState.Hidden, vm.uiState.value.row)
        repo.connect = TripItResult.ProRequired
        vm.connect()
        runCurrent()
        assertTrue(vm.uiState.value.showPaywall)
        vm.paywallShown()
        assertFalse(vm.uiState.value.showPaywall)
    }

    @Test
    fun syncRefreshesFlightsWhenSomethingWasAdded() = runTest(main.dispatcher) {
        repo.status = TripItResult.Ok(TripItStatus(true, Instant.parse("2026-11-27T09:00:00Z"), null))
        val vm = TripItViewModel(repo, flights, links)
        runCurrent()
        assertTrue(vm.uiState.value.row is TripItRowState.Connected)
        vm.sync()
        runCurrent()
        assertEquals(TripItSyncNotice(added = 2, notFound = 0), vm.uiState.value.syncNotice)
        assertEquals(1, flights.refreshCalls)

        repo.sync = TripItResult.Ok(TripItSyncSummary(segments = 3, existing = 3))
        vm.sync()
        runCurrent()
        assertEquals(TripItMessage.SYNCED_NOTHING_NEW, vm.uiState.value.message)
        assertNull(vm.uiState.value.syncNotice)
        assertEquals(1, flights.refreshCalls)
    }

    @Test
    fun revokedOffersConnectAgain() = runTest(main.dispatcher) {
        repo.status = TripItResult.Ok(TripItStatus(true, null, TripItLastError.REVOKED))
        val vm = TripItViewModel(repo, flights, links)
        runCurrent()
        assertEquals(true, (vm.uiState.value.row as TripItRowState.Connected).needsReconnect)
        repo.status = TripItResult.Ok(TripItStatus(true, null, null))
        repo.sync = TripItResult.Revoked
        vm.sync()
        runCurrent()
        assertEquals(TripItMessage.REVOKED, vm.uiState.value.message)
        assertEquals(true, (vm.uiState.value.row as TripItRowState.Connected).needsReconnect)
    }

    @Test
    fun disconnect() = runTest(main.dispatcher) {
        repo.status = TripItResult.Ok(TripItStatus(true, null, null))
        val vm = TripItViewModel(repo, flights, links)
        runCurrent()
        vm.disconnect()
        runCurrent()
        assertEquals(TripItRowState.Disconnected, vm.uiState.value.row)
        assertEquals(TripItMessage.DISCONNECTED, vm.uiState.value.message)
    }

    @Test
    fun connectedDeepLinkRefreshesStatusAndLaterTheFlights() = runTest(main.dispatcher) {
        val vm = TripItViewModel(repo, flights, links)
        vm.firstSyncWait = 6.seconds
        runCurrent()
        val before = repo.statusCalls
        repo.status = TripItResult.Ok(TripItStatus(true, null, null))
        links.offer("heygate://tripit/connected")
        runCurrent()
        assertEquals(TripItMessage.CONNECTED, vm.uiState.value.message)
        assertTrue(vm.uiState.value.row is TripItRowState.Connected)
        assertTrue(repo.statusCalls > before)
        assertNull(links.pending.value)
        assertEquals(0, flights.refreshCalls)
        advanceTimeBy(6_001)
        runCurrent()
        assertEquals(1, flights.refreshCalls)
    }

    @Test
    fun callbackCodeIsCompletedAsTheSignedInUser() = runTest(main.dispatcher) {
        val vm = TripItViewModel(repo, flights, links)
        vm.firstSyncWait = 6.seconds
        runCurrent()
        repo.status = TripItResult.Ok(TripItStatus(true, null, null))
        links.offer("heygate://tripit/callback?code=$CODE")
        runCurrent()
        assertEquals(listOf(CODE), repo.completed)
        assertEquals(TripItMessage.CONNECTED, vm.uiState.value.message)
        assertFalse(vm.uiState.value.isConnecting)
        assertTrue(vm.uiState.value.row is TripItRowState.Connected)
        assertNull(links.pending.value)
        advanceTimeBy(6_001)
        runCurrent()
        assertEquals(1, flights.refreshCalls)
    }

    @Test
    fun callbackCodeFromAnotherAccount() = runTest(main.dispatcher) {
        val vm = TripItViewModel(repo, flights, links)
        runCurrent()
        repo.complete = TripItResult.NotYourLink
        links.offer("heygate://tripit/callback?code=$CODE")
        runCurrent()
        assertEquals(TripItMessage.NOT_YOUR_LINK, vm.uiState.value.message)
        assertEquals(TripItRowState.Disconnected, vm.uiState.value.row)
        assertFalse(vm.uiState.value.isConnecting)
        assertEquals(0, flights.refreshCalls)

        repo.complete = TripItResult.Expired
        links.offer("heygate://tripit/callback?code=$CODE")
        runCurrent()
        assertEquals(TripItMessage.CONNECT_EXPIRED, vm.uiState.value.message)
    }

    @Test
    fun malformedCallbackIsNotSentToTheServer() = runTest(main.dispatcher) {
        val vm = TripItViewModel(repo, flights, links)
        runCurrent()
        links.offer("heygate://tripit/callback?code=nope")
        runCurrent()
        assertTrue(repo.completed.isEmpty())
        assertEquals(TripItMessage.CONNECT_FAILED, vm.uiState.value.message)
    }

    @Test
    fun syncCooldown() = runTest(main.dispatcher) {
        repo.status = TripItResult.Ok(TripItStatus(true, null, null))
        val vm = TripItViewModel(repo, flights, links)
        runCurrent()
        repo.sync = TripItResult.Cooldown(42)
        vm.sync()
        runCurrent()
        assertEquals(TripItMessage.SYNC_COOLDOWN, vm.uiState.value.message)
        assertEquals(42, vm.uiState.value.cooldownSeconds)
        assertFalse(vm.uiState.value.isSyncing)
        assertTrue(vm.uiState.value.row is TripItRowState.Connected)
        vm.messageShown()
        assertNull(vm.uiState.value.cooldownSeconds)
    }

    @Test
    fun failedDeepLink() = runTest(main.dispatcher) {
        val vm = TripItViewModel(repo, flights, links)
        runCurrent()
        links.offer("heygate://tripit/failed?error=denied")
        runCurrent()
        assertEquals(TripItMessage.CONNECT_DENIED, vm.uiState.value.message)
    }

    @Test
    fun statusErrorOnFirstLoadIsUnknown() = runTest(main.dispatcher) {
        repo.status = TripItResult.Offline
        val vm = TripItViewModel(repo, flights, links)
        runCurrent()
        assertEquals(TripItRowState.Unknown, vm.uiState.value.row)
    }
}
