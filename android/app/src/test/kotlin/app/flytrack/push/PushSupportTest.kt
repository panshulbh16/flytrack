package app.flytrack.push

import app.flytrack.core.result.ActionResult
import app.flytrack.data.repo.DeviceRegistration
import app.flytrack.testing.FakeDeviceRepository
import app.flytrack.testing.FakeSettingsStore
import app.flytrack.testing.TestData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushSupportTest {
    @Test
    fun `makers are recognised from the manufacturer string`() {
        assertEquals(DeviceMaker.XIAOMI, deviceMaker("Xiaomi", "Redmi"))
        assertEquals(DeviceMaker.XIAOMI, deviceMaker("POCO"))
        assertEquals(DeviceMaker.VIVO, deviceMaker("vivo"))
        assertEquals(DeviceMaker.VIVO, deviceMaker("iQOO"))
        assertEquals(DeviceMaker.REALME, deviceMaker("realme"))
        assertEquals(DeviceMaker.OPPO, deviceMaker("OPPO"))
        assertEquals(DeviceMaker.ONEPLUS, deviceMaker("OnePlus"))
        assertEquals(DeviceMaker.SAMSUNG, deviceMaker("samsung"))
        assertEquals(DeviceMaker.OTHER, deviceMaker("Google", "Pixel"))
    }

    @Test
    fun `battery links try the maker first and always end at system settings`() {
        val xiaomi = batterySettingsTargets(DeviceMaker.XIAOMI)
        assertTrue(xiaomi.first() is SettingsTarget.Component)
        val actions = xiaomi.filterIsInstance<SettingsTarget.Action>()
        assertEquals(SettingsActions.IGNORE_BATTERY_OPTIMIZATIONS, actions.first().action)
        assertEquals(SettingsActions.APPLICATION_DETAILS, actions.last().action)
        val generic = batterySettingsTargets(DeviceMaker.OTHER)
        assertEquals(2, generic.size)
        assertTrue(generic.all { it is SettingsTarget.Action })
    }

    @Test
    fun `permission rationale is shown once on android 13 and up`() {
        assertTrue(shouldShowNotificationRationale(sdkInt = 33, runtimeGranted = false, alreadyAsked = false))
        assertFalse(shouldShowNotificationRationale(sdkInt = 33, runtimeGranted = true, alreadyAsked = false))
        assertFalse(shouldShowNotificationRationale(sdkInt = 33, runtimeGranted = false, alreadyAsked = true))
        assertFalse(shouldShowNotificationRationale(sdkInt = 32, runtimeGranted = false, alreadyAsked = false))
    }

    @Test
    fun `a blocked channel or a denied permission is visible`() {
        val health = notificationHealth(
            sdkInt = 34,
            runtimeGranted = false,
            appNotificationsEnabled = false,
            criticalExists = true,
            criticalBlocked = true,
            updatesExists = false,
            updatesBlocked = false,
            batteryUnrestricted = false,
            maker = DeviceMaker.VIVO,
            pushConfigured = false,
        )
        assertEquals(PermissionHealth.DENIED, health.permission)
        assertEquals(ChannelHealth.BLOCKED, health.criticalChannel)
        assertEquals(ChannelHealth.MISSING, health.updatesChannel)
        assertTrue(health.notificationsBlocked)
        assertTrue(health.anyChannelBlocked)
        assertFalse(health.pushConfigured)
    }

    @Test
    fun `before android 13 a system block is not a runtime denial`() {
        val health = permissionHealth(sdkInt = 28, runtimeGranted = false, appNotificationsEnabled = false)
        assertEquals(PermissionHealth.NOT_REQUIRED_BUT_BLOCKED, health)
        assertEquals(PermissionHealth.NOT_REQUIRED, permissionHealth(28, runtimeGranted = false, appNotificationsEnabled = true))
    }

    @Test
    fun `registration is skipped when the token is already stored for this user`() = runTest {
        val settings = FakeSettingsStore(lastDeviceRegistration = DeviceRegistration("token-a", TestData.USER_ID))
        val devices = FakeDeviceRepository()
        val registrar = PushRegistrar(devices, settings, DeviceFacts("0.1.0", "14", "en-IN", "Asia/Kolkata")) { "token-a" }

        assertEquals(ActionResult.Success, registrar.syncForUser(TestData.USER_ID))
        assertTrue(devices.registrations.isEmpty())

        registrar.syncForUser("someone-else")
        assertEquals("token-a", devices.registrations.single().fcmToken)
        assertEquals("0.1.0", devices.registrations.single().appVersion)
        assertEquals("Asia/Kolkata", devices.registrations.single().timezone)
    }

    @Test
    fun `sign out unregisters the stored token and does nothing without one`() = runTest {
        val settings = FakeSettingsStore(lastDeviceRegistration = DeviceRegistration("token-a", TestData.USER_ID))
        val devices = FakeDeviceRepository()
        val registrar = PushRegistrar(devices, settings, DeviceFacts(null, null, null, null)) { null }

        registrar.beforeSignOut()
        assertEquals(listOf("token-a"), devices.unregisteredTokens)

        val empty = PushRegistrar(FakeDeviceRepository(), FakeSettingsStore(), DeviceFacts(null, null, null, null)) { null }
        assertEquals(null, empty.beforeSignOut())
    }

    @Test
    fun `sync work retries offline a few times and does not retry a missing flight`() {
        assertEquals(SyncWorkOutcome.RETRY, syncWorkOutcome(ActionResult.Offline, attempt = 1))
        assertEquals(SyncWorkOutcome.FAILURE, syncWorkOutcome(ActionResult.Offline, attempt = 3))
        assertEquals(SyncWorkOutcome.SUCCESS, syncWorkOutcome(ActionResult.NotFound, attempt = 1))
        assertEquals(SyncWorkOutcome.SUCCESS, syncWorkOutcome(ActionResult.NotConfigured, attempt = 1))
        assertEquals(SyncWorkOutcome.SUCCESS, syncWorkOutcome(ActionResult.Success, attempt = 1))
    }
}
