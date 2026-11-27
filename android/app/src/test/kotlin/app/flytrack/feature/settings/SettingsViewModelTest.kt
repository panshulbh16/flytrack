package app.flytrack.feature.settings

import app.flytrack.core.model.AlertType
import app.flytrack.core.result.ActionResult
import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendConfig
import app.flytrack.data.repo.DeviceRegistration
import app.flytrack.feature.gmailimport.GmailConnection
import app.flytrack.push.ChannelHealth
import app.flytrack.push.DeviceMaker
import app.flytrack.push.NotificationHealth
import app.flytrack.push.NotificationHealthSource
import app.flytrack.push.PermissionHealth
import app.flytrack.push.PushAvailability
import app.flytrack.push.PushInitializer
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.FakeClock
import app.flytrack.testing.FakeDeviceRepository
import app.flytrack.testing.FakeGmailApi
import app.flytrack.testing.FakeGmailAuth
import app.flytrack.testing.FakeGmailNotifier
import app.flytrack.testing.FakeGmailSuggestionStore
import app.flytrack.testing.FakeProfileRepository
import app.flytrack.testing.FakeSettingsStore
import app.flytrack.testing.FakeWorkScheduler
import app.flytrack.testing.GmailFixtures
import app.flytrack.testing.MainDispatcherRule
import app.flytrack.testing.TestData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val auth = FakeAuthRepository()
    private val settings = FakeSettingsStore()
    private val devices = FakeDeviceRepository()
    private val health = FakeHealth()
    private val push = FakePush(devices, settings)
    private val gmailAuth = FakeGmailAuth()
    private val gmailStore = FakeGmailSuggestionStore()
    private val gmailNotifier = FakeGmailNotifier()
    private val gmailWork = FakeWorkScheduler()
    private val gmailConnection = GmailConnection(gmailAuth, FakeGmailApi(), gmailStore, settings, gmailNotifier, gmailWork)

    private fun viewModel(google: Boolean = true) = SettingsViewModel(
        auth = auth,
        profiles = FakeProfileRepository(),
        settings = settings,
        defaultAlerts = MemoryAlerts(),
        healthSource = health,
        push = push,
        config = BackendConfig("https://example.supabase.co", "anon", if (google) "web-client" else ""),
        clock = FakeClock(),
        gmailConnection = gmailConnection,
        gmailWork = gmailWork,
        version = version,
    )

    @Test
    fun `link is refused when google is not configured and a cancel is quiet`() = runTest {
        val vm = viewModel(google = false)
        assertFalse(vm.beginGoogleLink())
        vm.uiState.value.let { assertEquals(SettingsMessage.GoogleNotConfigured, it.message) }

        val ready = viewModel()
        assertTrue(ready.beginGoogleLink())
        ready.onGoogleAttempt(GoogleSignInAttempt.Cancelled)
        assertFalse(ready.uiState.value.linking)
        assertNull(ready.uiState.value.message)

        ready.beginGoogleLink()
        ready.onGoogleAttempt(GoogleSignInAttempt.NoCredential)
        assertEquals(SettingsMessage.NoGoogleCredential, ready.uiState.value.message)
    }

    @Test
    fun `a google token is linked and an account already in use is held`() = runTest {
        val vm = viewModel()
        vm.onGoogleAttempt(GoogleSignInAttempt.Token("id-token"))
        assertEquals(listOf("id-token"), auth.linkedIdTokens)
        assertEquals(SettingsMessage.Linked, vm.uiState.value.message)

        auth.nextResult = AuthResult.GoogleAccountInUse
        vm.onGoogleAttempt(GoogleSignInAttempt.Token("other-token"))
        assertEquals(SettingsMessage.GoogleInUse, vm.uiState.value.message)
        assertEquals("other-token", vm.uiState.value.pendingGoogleToken)
    }

    @Test
    fun `sign out unregisters the device before the session changes`() = runTest {
        settings.setLastDeviceRegistration(DeviceRegistration("token-a", TestData.USER_ID))
        val order = mutableListOf<String>()
        val orderingDevices = object : app.flytrack.data.repo.DeviceRepository by devices {
            override suspend fun unregisterDevice(fcmToken: String): ActionResult {
                order += "unregister"
                return devices.unregisterDevice(fcmToken)
            }
        }
        val orderingAuth = object : app.flytrack.data.repo.AuthRepository by auth {
            override suspend fun signOut(): AuthResult {
                order += "signOut"
                return auth.signOut()
            }
        }
        val vm = SettingsViewModel(
            auth = orderingAuth,
            profiles = FakeProfileRepository(),
            settings = settings,
            defaultAlerts = MemoryAlerts(),
            healthSource = health,
            push = FakePush(orderingDevices, settings),
            config = BackendConfig("https://example.supabase.co", "anon", "web"),
            clock = FakeClock(),
            gmailConnection = gmailConnection,
            gmailWork = gmailWork,
            version = version,
        )
        vm.signOut()
        assertEquals(listOf("unregister", "signOut"), order)
        assertEquals(SettingsMessage.SignedOut, vm.uiState.value.message)
    }

    @Test
    fun `switching to a google account in use keeps the device on this account`() = runTest {
        settings.setLastDeviceRegistration(DeviceRegistration("token-a", TestData.USER_ID))
        val vm = viewModel()
        auth.nextResult = AuthResult.GoogleAccountInUse
        vm.onGoogleAttempt(GoogleSignInAttempt.Token("other-token"))

        auth.nextResult = AuthResult.Failed("network")
        vm.signInWithPendingGoogle()
        assertEquals(SettingsMessage.Failed, vm.uiState.value.message)
        assertEquals(emptyList<String>(), devices.unregisteredTokens)
    }

    @Test
    fun `an alert group mute is stored and a locked pro group is not`() = runTest {
        val alerts = MemoryAlerts()
        val vm = SettingsViewModel(
            auth = auth,
            profiles = FakeProfileRepository(),
            settings = settings,
            defaultAlerts = alerts,
            healthSource = health,
            push = push,
            config = BackendConfig("https://example.supabase.co", "anon", "web"),
            clock = FakeClock(),
            gmailConnection = gmailConnection,
            gmailWork = gmailWork,
            version = version,
        )
        vm.uiState.value
        vm.setAlertGroup(app.flytrack.feature.flights.presentation.AlertGroup.BOARDING, enabled = false)
        // P3: the boarding switch also covers "gate closed".
        assertEquals(setOf(AlertType.BOARDING, AlertType.GATE_CLOSED), alerts.muted.value)
        vm.setAlertGroup(app.flytrack.feature.flights.presentation.AlertGroup.INBOUND_AIRCRAFT, enabled = false)
        assertEquals(setOf(AlertType.BOARDING, AlertType.GATE_CLOSED), alerts.muted.value)
    }

    @Test
    fun `a family member's Pro alert groups are unlocked`() = runTest {
        val social = app.flytrack.feature.social.InMemorySocialStore()
        val vm = SettingsViewModel(
            auth = auth,
            profiles = FakeProfileRepository(),
            settings = settings,
            defaultAlerts = MemoryAlerts(),
            healthSource = health,
            push = push,
            config = BackendConfig("https://example.supabase.co", "anon", "web"),
            clock = FakeClock(),
            gmailConnection = gmailConnection,
            gmailWork = gmailWork,
            version = version,
            social = social,
        )
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        val inbound = app.flytrack.feature.flights.presentation.AlertGroup.INBOUND_AIRCRAFT
        fun locked() = vm.uiState.value.alerts.single { it.group == inbound }.locked
        runCurrent()
        assertTrue(locked())

        social.family.value = app.flytrack.feature.social.FamilyGrant(TestData.USER_ID, "Asha")
        runCurrent()
        assertFalse(locked())
    }

    // ---- Gmail --------------------------------------------------------------------------------

    @Test
    fun `the gmail section appears once connected and says when access was lost`() = runTest {
        val vm = viewModel()
        assertNull(vm.uiState.value.gmail)

        settings.setGmailAccountEmail("me@gmail.com")
        assertEquals(GmailSettingsUi("me@gmail.com", autoCheck = true, needsReconnect = false), vm.uiState.value.gmail)

        settings.setGmailNeedsReconnect(true)
        assertEquals(true, vm.uiState.value.gmail?.needsReconnect)
    }

    @Test
    fun `turning auto-check off cancels the background check and turning it on schedules it`() = runTest {
        settings.setGmailAccountEmail("me@gmail.com")
        val vm = viewModel()

        vm.setGmailAutoCheck(false)
        assertFalse(settings.gmailAutoCheck.first())
        assertEquals(false, vm.uiState.value.gmail?.autoCheck)
        assertEquals(1, gmailWork.cancelled)
        assertEquals(0, gmailWork.scheduled)

        vm.setGmailAutoCheck(true)
        assertTrue(settings.gmailAutoCheck.first())
        assertEquals(1, gmailWork.scheduled)
        assertEquals(1, gmailWork.cancelled)
    }

    @Test
    fun `disconnecting gmail revokes access, deletes suggestions and hides the section`() = runTest {
        settings.setGmailAccountEmail("me@gmail.com")
        gmailStore.insert(listOf(GmailFixtures.suggestion()))
        val vm = viewModel()

        vm.disconnectGmail()

        assertEquals(listOf("me@gmail.com"), gmailAuth.revokedEmails)
        assertTrue(gmailStore.all().isEmpty())
        assertEquals(1, gmailWork.cancelled)
        assertEquals(1, gmailNotifier.cancelAllCalls)
        assertNull(vm.uiState.value.gmail)
    }
}

private class MemoryAlerts : DefaultAlertPreferences {
    override val muted = MutableStateFlow<Set<AlertType>>(emptySet())
    override suspend fun setMuted(types: Set<AlertType>, muted: Boolean) {
        this.muted.value = if (muted) this.muted.value + types else this.muted.value - types
    }
}

private class FakeHealth : NotificationHealthSource {
    override fun read() = NotificationHealth(
        permission = PermissionHealth.GRANTED,
        criticalChannel = ChannelHealth.OK,
        updatesChannel = ChannelHealth.OK,
        batteryUnrestricted = true,
        maker = DeviceMaker.OTHER,
        pushConfigured = true,
    )
    override fun openNotificationSettings() = Unit
    override fun openChannelSettings(channelId: String) = Unit
    override fun openBatterySettings() = Unit
}

private class FakePush(
    private val devices: app.flytrack.data.repo.DeviceRepository,
    private val settings: FakeSettingsStore,
) : PushInitializer {
    override val availability = MutableStateFlow<PushAvailability>(PushAvailability.Ready)
    override fun onAppStart() = Unit
    override suspend fun beforeSignOut(): ActionResult? {
        val token = settings.lastDeviceRegistration.first()?.fcmToken ?: return null
        return devices.unregisterDevice(token)
    }
}

private val version = object : AppVersion {
    override val name = "0.1.0"
}
