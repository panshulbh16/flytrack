package app.flytrack.data.repo

import app.cash.turbine.test
import app.flytrack.core.model.Plan
import app.flytrack.core.result.ActionResult
import app.flytrack.core.result.AuthResult
import app.flytrack.data.local.ProfileEntity
import app.flytrack.data.remote.BackendException
import app.flytrack.data.remote.FlyTrackJson
import app.flytrack.data.remote.dto.AirportDto
import app.flytrack.data.remote.dto.ProfileDto
import app.flytrack.data.remote.dto.RegisterDeviceParams
import app.flytrack.data.remote.toEntity
import app.flytrack.feature.gmailimport.GmailConnection
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.FakeBackendApi
import app.flytrack.testing.FakeGmailApi
import app.flytrack.testing.FakeGmailAuth
import app.flytrack.testing.FakeGmailNotifier
import app.flytrack.testing.FakeGmailSuggestionStore
import app.flytrack.testing.FakeSettingsStore
import app.flytrack.testing.FakeWorkScheduler
import app.flytrack.testing.Fixtures
import app.flytrack.testing.GmailFixtures
import app.flytrack.testing.InMemoryDatabase
import app.flytrack.testing.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class OtherRepositoriesTest {
    private val scope = TestScope()
    private val io = UnconfinedTestDispatcher(scope.testScheduler)
    private val api = FakeBackendApi()
    private val auth = FakeAuthRepository()
    private val db = InMemoryDatabase()
    private val settings = FakeSettingsStore()
    private val offline get() = BackendException.Network(IOException("offline"))
    private val token = "fcm-token-0123456789abcdefghij"

    // ---- DeviceRepository -------------------------------------------------------------------------

    private val devices = DeviceRepositoryImpl(api, auth, settings, io)

    @Test
    fun `registerDevice passes every parameter to the RPC and remembers the registration`() = scope.runTest {
        val result = devices.registerDevice(token, appVersion = "0.1.0", osVersion = "16", locale = "en-IN", timezone = "Asia/Kolkata")

        assertEquals(ActionResult.Success, result)
        assertEquals(listOf(RegisterDeviceParams(token, "0.1.0", "16", "en-IN", "Asia/Kolkata")), api.devices)
        assertEquals(DeviceRegistration(token, TestData.USER_ID), settings.lastDeviceRegistration.first())
    }

    @Test
    fun `registerDevice signs in first when there is no session yet`() = scope.runTest {
        val signedOut = FakeAuthRepository.signedOut()
        val repo = DeviceRepositoryImpl(api, signedOut, settings, io)

        assertEquals(ActionResult.Success, repo.registerDevice(token))
        assertEquals(1, signedOut.ensureSignedInCalls)
        assertEquals(1, api.devices.size)
    }

    @Test
    fun `a failed registration is not remembered`() = scope.runTest {
        api.failure = offline
        assertEquals(ActionResult.Offline, devices.registerDevice(token))

        api.failure = BackendException.Http(400, "22023", "invalid_token")
        assertEquals(ActionResult.Failed("invalid_token"), devices.registerDevice("short"))

        auth.nextResult = AuthResult.NotConfigured
        assertEquals(ActionResult.NotConfigured, devices.registerDevice(token))
        assertNull(settings.lastDeviceRegistration.first())
    }

    @Test
    fun `unregisterDevice removes the token and forgets the registration`() = scope.runTest {
        devices.registerDevice(token)

        assertEquals(ActionResult.Success, devices.unregisterDevice(token))

        assertTrue(api.devices.isEmpty())
        assertNull(settings.lastDeviceRegistration.first())
    }

    @Test
    fun `notifications can be switched off for this phone only after it was registered`() = scope.runTest {
        assertEquals(ActionResult.NotFound, devices.setNotificationsEnabled(token, enabled = false))

        devices.registerDevice(token)
        assertEquals(ActionResult.Success, devices.setNotificationsEnabled(token, enabled = false))
        assertEquals(false, api.deviceNotificationsEnabled[token])

        api.failure = offline
        assertEquals(ActionResult.Offline, devices.setNotificationsEnabled(token, enabled = true))
        assertEquals(false, api.deviceNotificationsEnabled[token])
    }

    // ---- AirportRepository ------------------------------------------------------------------------

    private val airports = AirportRepositoryImpl(api, auth, db.airportDao, io)
    private val fixtureAirports =
        FlyTrackJson.decodeFromString(ListSerializer(AirportDto.serializer()), Fixtures.read("airports.json"))

    @Test
    fun `airport is fetched from the backend once and then served from Room`() = scope.runTest {
        fixtureAirports.forEach { api.serverAirports[it.iata] = it }

        assertEquals("Mumbai", airports.getAirport(" bom ")?.city)
        assertEquals("Mumbai", airports.getAirport("BOM")?.city)

        assertEquals(listOf("airports(BOM)"), api.calls)
    }

    @Test
    fun `ensureCached requests only the missing airports`() = scope.runTest {
        fixtureAirports.forEach { api.serverAirports[it.iata] = it }
        db.airportDao.upsert(listOf(fixtureAirports.first { it.iata == "DEL" }.toEntity()))

        assertEquals(ActionResult.Success, airports.ensureCached(listOf("DEL", "BOM", "bom", "")))
        assertEquals(listOf("airports(BOM)"), api.calls)

        assertEquals(ActionResult.Success, airports.ensureCached(listOf("DEL", "BOM")))
        assertEquals("everything cached: no request", 1, api.calls.size)
    }

    @Test
    fun `unknown or unreachable airports are null`() = scope.runTest {
        assertNull(airports.getAirport("ZZZ"))
        api.failure = offline
        assertNull(airports.getAirport("DEL"))
        assertEquals(ActionResult.Offline, airports.ensureCached(listOf("DEL")))
    }

    // ---- ProfileRepository ------------------------------------------------------------------------

    private val profiles = ProfileRepositoryImpl(api, auth, db.profileDao, io)

    @Test
    fun `profile is null until refreshed, then follows Room`() = scope.runTest {
        api.serverProfile = ProfileDto(id = TestData.USER_ID, displayName = "Asha", plan = "pro", proUntil = null)

        profiles.observeProfile().test {
            assertNull(awaitItem())
            assertEquals(ActionResult.Success, profiles.refresh())
            val profile = awaitItem()!!
            assertEquals("Asha", profile.displayName)
            assertEquals(Plan.PRO, profile.plan)
            assertTrue(profile.isPro(TestData.NOW))
        }
    }

    @Test
    fun `profile of another user is never shown`() = scope.runTest {
        db.profileDao.upsert(ProfileEntity("someone-else", "Old user", "pro", null))
        assertNull(profiles.observeProfile().first())

        auth.setSession(SessionState.SignedOut)
        assertNull(profiles.observeProfile().first())
    }

    @Test
    fun `profile refresh failures`() = scope.runTest {
        api.serverProfile = null
        assertEquals(ActionResult.NotFound, profiles.refresh())
        api.failure = offline
        assertEquals(ActionResult.Offline, profiles.refresh())
    }

    // ---- local wipe -------------------------------------------------------------------------------

    @Test
    fun `wiping local user data clears flights, tracking, profile, the device registration and gmail but keeps airports`() = scope.runTest {
        val flight = FakeBackendApi.fixtureFlights()[0]
        db.flightDao.upsert(listOf(flight.toEntity()))
        db.airportDao.upsert(fixtureAirports.map { it.toEntity() })
        db.trackedDao.upsert(FakeBackendApi.fixtureUserFlights().map { it.toEntity() })
        db.profileDao.upsert(ProfileEntity(TestData.USER_ID, null, "free", null))
        settings.setLastDeviceRegistration(DeviceRegistration(token, TestData.USER_ID))
        val gmailAuth = FakeGmailAuth()
        val gmailStore = FakeGmailSuggestionStore()
        val gmailNotifier = FakeGmailNotifier()
        val gmailWork = FakeWorkScheduler()
        val gmail = GmailConnection(gmailAuth, FakeGmailApi(), gmailStore, settings, gmailNotifier, gmailWork)
        settings.setGmailAccountEmail("me@gmail.com")
        gmailStore.insert(listOf(GmailFixtures.suggestion()))

        RoomLocalUserData(db.flightDao, db.trackedDao, db.profileDao, settings, db.transactions, gmail).clear()

        assertTrue(db.flights.value.isEmpty())
        assertTrue(db.tracked.value.isEmpty())
        assertTrue(db.profiles.value.isEmpty())
        assertEquals(2, db.airports.value.size)
        assertNull(settings.lastDeviceRegistration.first())
        // Sign-out and a user switch both go through clear(): the stored Gmail account is revoked and local Gmail state is gone.
        assertEquals(listOf("me@gmail.com"), gmailAuth.revokedEmails)
        assertTrue(gmailStore.all().isEmpty())
        assertNull(settings.gmailAccountEmail.first())
        assertEquals(1, gmailWork.cancelled)
        assertEquals(1, gmailNotifier.cancelAllCalls)
    }
}
