package app.flytrack.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Uses a real DataStore on a temp file: the keys, defaults and persistence are what is under test. */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreSettingsStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val scope = TestScope()

    private fun store(file: File = File(folder.root, "settings.preferences_pb")) = DataStoreSettingsStore(
        PreferenceDataStoreFactory.create(scope = scope.backgroundScope + UnconfinedTestDispatcher(scope.testScheduler)) { file },
    )

    private operator fun kotlinx.coroutines.CoroutineScope.plus(context: kotlin.coroutines.CoroutineContext) =
        kotlinx.coroutines.CoroutineScope(coroutineContext + context)

    @Test
    fun `defaults are privacy-preserving`() = scope.runTest {
        val store = store()
        assertFalse("cloud parsing is opt-in", store.cloudTicketParsingConsent.first())
        assertFalse(store.notificationPermissionAsked.first())
        assertNull(store.lastDeviceRegistration.first())
    }

    @Test
    fun `values are stored and read back`() = scope.runTest {
        val store = store()

        store.setCloudTicketParsingConsent(true)
        store.setNotificationPermissionAsked(true)
        store.setLastDeviceRegistration(DeviceRegistration("token-1", "user-1"))

        assertTrue(store.cloudTicketParsingConsent.first())
        assertTrue(store.notificationPermissionAsked.first())
        assertEquals(DeviceRegistration("token-1", "user-1"), store.lastDeviceRegistration.first())

        store.setCloudTicketParsingConsent(false)
        store.setLastDeviceRegistration(null)
        assertFalse(store.cloudTicketParsingConsent.first())
        assertNull(store.lastDeviceRegistration.first())
    }
}
