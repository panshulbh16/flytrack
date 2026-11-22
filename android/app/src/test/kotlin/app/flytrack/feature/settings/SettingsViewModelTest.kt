package app.flytrack.feature.settings

import app.flytrack.core.model.AlertType
import app.flytrack.core.result.ActionResult
import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendConfig
import app.flytrack.data.repo.DeviceRegistration
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
import app.flytrack.testing.FakeProfileRepository
import app.flytrack.testing.FakeSettingsStore
import app.flytrack.testing.MainDispatcherRule
import app.flytrack.testing.TestData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

    private fun viewModel(google: Boolean = true) = SettingsViewModel(
        auth = auth,
        profiles = FakeProfileRepository(),
        settings = settings,
        defaultAlerts = MemoryAlerts(),
        healthSource = health,
        push = push,
        config = BackendConfig("https://example.supabase.co", "anon", if (google) "web-client" else ""),
        clock = FakeClock(),
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
            version = version,
        )
        vm.signOut()
        assertEquals(listOf("unregister", "signOut"), order)
        assertEquals(SettingsMessage.SignedOut, vm.uiState.value.message)
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
            version = version,
        )
        vm.uiState.value
        vm.setAlertGroup(app.flytrack.feature.flights.presentation.AlertGroup.BOARDING, enabled = false)
        assertEquals(setOf(AlertType.BOARDING), alerts.muted.value)
        vm.setAlertGroup(app.flytrack.feature.flights.presentation.AlertGroup.INBOUND_AIRCRAFT, enabled = false)
        assertEquals(setOf(AlertType.BOARDING), alerts.muted.value)
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
