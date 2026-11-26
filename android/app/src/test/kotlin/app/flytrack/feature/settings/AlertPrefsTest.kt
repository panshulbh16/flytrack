package app.flytrack.feature.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.AlertPrefsDto
import app.flytrack.data.remote.BackendException
import app.flytrack.feature.passport.FakeTravelApi
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.MainDispatcherRule
import app.flytrack.testing.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
class AlertPrefsTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val folder = TemporaryFolder()

    private val scope = TestScope()
    private val api = FakeTravelApi()
    private val auth = FakeAuthRepository()
    private val device = object : DeviceLocale {
        override var language = "en"
        override var timeZone = "Asia/Dubai"
    }

    private fun repo(file: File = File(folder.root, "prefs.preferences_pb")) = SupabaseAlertPrefsRepository(
        api,
        auth,
        PreferenceDataStoreFactory.create(scope = scope.backgroundScope + UnconfinedTestDispatcher(scope.testScheduler)) { file },
        device,
        UnconfinedTestDispatcher(scope.testScheduler),
    )

    private operator fun kotlinx.coroutines.CoroutineScope.plus(context: kotlin.coroutines.CoroutineContext) =
        kotlinx.coroutines.CoroutineScope(coroutineContext + context)

    // ---- pure helpers -------------------------------------------------------------------------

    @Test
    fun `postgres times parse to the minute and format as HH mm`() {
        assertEquals(LocalTime.of(23, 0), parsePgTime("23:00:00"))
        assertEquals(LocalTime.of(7, 30), parsePgTime("07:30"))
        assertEquals(LocalTime.of(6, 15), parsePgTime("06:15:00.5"))
        assertNull(parsePgTime("24:00:00"))
        assertNull(parsePgTime("noon"))
        assertNull(parsePgTime(null))
        assertEquals("07:05", formatPgTime(LocalTime.of(7, 5)))
    }

    @Test
    fun `alert language defaults to Hindi only on a Hindi phone`() {
        assertEquals(AlertLanguage.HI, defaultAlertLanguage("hi"))
        assertEquals(AlertLanguage.HI, defaultAlertLanguage("hi-IN"))
        assertEquals(AlertLanguage.HI, defaultAlertLanguage("hi_IN"))
        assertEquals(AlertLanguage.EN, defaultAlertLanguage("en"))
        assertEquals(AlertLanguage.EN, defaultAlertLanguage("mr"))
        assertEquals(AlertLanguage.EN, defaultAlertLanguage(null))
        assertEquals(listOf("en", "hi", "hinglish"), AlertLanguage.entries.map { it.wire })
        assertEquals(AlertLanguage.EN, AlertLanguage.fromWire("tamil"))
    }

    @Test
    fun `the server row maps to prefs with the contract defaults`() {
        assertEquals(AlertPrefs(), AlertPrefsDto().toAlertPrefs())
        assertEquals(
            AlertPrefs(true, LocalTime.of(22, 30), LocalTime.of(6, 0), "Europe/London", AlertLanguage.HINGLISH),
            AlertPrefsDto(true, "22:30:00", "06:00:00", "Europe/London", "hinglish").toAlertPrefs(),
        )
    }

    @Test
    fun `patches touch only user columns and quiet-hours changes carry the phone's zone`() {
        assertEquals(
            buildJsonObject { put("quiet_hours_enabled", true); put("quiet_tz", "Asia/Kolkata") },
            alertPrefsPatch(AlertPrefsChange.QuietHoursEnabled(true), "Asia/Kolkata"),
        )
        assertEquals(
            buildJsonObject { put("quiet_start", "22:15"); put("quiet_tz", "Asia/Kolkata") },
            alertPrefsPatch(AlertPrefsChange.QuietStart(LocalTime.of(22, 15)), "Asia/Kolkata"),
        )
        assertEquals(
            buildJsonObject { put("quiet_end", "06:45"); put("quiet_tz", "Asia/Kolkata") },
            alertPrefsPatch(AlertPrefsChange.QuietEnd(LocalTime.of(6, 45)), "Asia/Kolkata"),
        )
        assertEquals(
            buildJsonObject { put("alert_language", "hinglish") },
            alertPrefsPatch(AlertPrefsChange.Language(AlertLanguage.HINGLISH), "Asia/Kolkata"),
        )
    }

    @Test
    fun `quiet start and end are written with ASCII digits whatever the phone's locale`() {
        val saved = java.util.Locale.getDefault()
        try {
            // Marathi and Nepali default to Devanagari digits; Postgres would reject "२२:१५".
            for (tag in listOf("mr-IN", "ne-NP", "ar-EG", "hi-IN")) {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag))
                assertEquals(tag, "22:15", formatPgTime(LocalTime.of(22, 15)))
                assertEquals(
                    tag,
                    JsonPrimitive("06:05"),
                    alertPrefsPatch(AlertPrefsChange.QuietEnd(LocalTime.of(6, 5)), "Asia/Kolkata")["quiet_end"],
                )
            }
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `a phone zone the server would reject is not sent and the old zone stays`() {
        assertEquals("Asia/Kolkata", acceptableQuietTz("Asia/Kolkata"))
        assertEquals("Asia/Calcutta", acceptableQuietTz("Asia/Calcutta"))
        assertEquals("America/Argentina/Buenos_Aires", acceptableQuietTz("America/Argentina/Buenos_Aires"))
        assertEquals("UTC", acceptableQuietTz("UTC"))
        assertNull(acceptableQuietTz("GMT+05:30"))
        assertNull(acceptableQuietTz("GMT+5"))
        assertNull(acceptableQuietTz("Etc/GMT-5"))
        assertNull(acceptableQuietTz("posix/Asia/Kolkata"))
        assertNull(acceptableQuietTz("localtime"))
        assertNull(acceptableQuietTz(""))
        assertNull(acceptableQuietTz(null))

        assertEquals(
            buildJsonObject { put("quiet_hours_enabled", true) },
            alertPrefsPatch(AlertPrefsChange.QuietHoursEnabled(true), "GMT+05:30"),
        )
        val before = AlertPrefs(quietTz = "Asia/Dubai")
        val after = before.apply(AlertPrefsChange.QuietHoursEnabled(true), "GMT+05:30")
        assertEquals("Asia/Dubai", after.quietTz)
        assertTrue(after.quietHoursEnabled)
        assertEquals("Asia/Kolkata", before.apply(AlertPrefsChange.QuietStart(LocalTime.NOON), "Asia/Kolkata").quietTz)
    }

    /** Every column the app writes must be one migration 15 grants to authenticated users. */
    @Test
    fun `patched columns are granted by the migration`() {
        val migrations = File("../../supabase/migrations")
        org.junit.Assume.assumeTrue(migrations.isDirectory)
        val sql = migrations.listFiles()!!.sortedBy { it.name }.joinToString("\n") { it.readText() }
        val granted = Regex("""grant update \(([^)]*)\) on public\.profiles to authenticated""").findAll(sql)
            .flatMap { m -> m.groupValues[1].split(',').map { it.trim() } }.toSet()
        val written = listOf(
            AlertPrefsChange.QuietHoursEnabled(true),
            AlertPrefsChange.QuietStart(LocalTime.NOON),
            AlertPrefsChange.QuietEnd(LocalTime.NOON),
            AlertPrefsChange.Language(AlertLanguage.HI),
        ).flatMap { alertPrefsPatch(it, "Asia/Kolkata").keys }.toSet()
        assertTrue("not granted: ${written - granted}", granted.containsAll(written))
        val checked = Regex("""alert_language in \(([^)]*)\)""").find(sql)!!.groupValues[1]
        assertEquals(AlertLanguage.entries.map { it.wire }, Regex("'([^']+)'").findAll(checked).map { it.groupValues[1] }.toList())
    }

    // ---- repository ---------------------------------------------------------------------------

    @Test
    fun `refresh loads the row and update writes it`() = scope.runTest {
        val repo = repo()
        assertNull(repo.prefs.value)
        assertTrue(repo.refresh() is AlertPrefsResult.Loaded)
        assertEquals(AlertPrefs(), repo.prefs.value)

        val result = repo.update(AlertPrefsChange.QuietStart(LocalTime.of(22, 0)))
        assertTrue(result is AlertPrefsResult.Loaded)
        assertEquals(LocalTime.of(22, 0), repo.prefs.value!!.quietStart)
        assertEquals("Asia/Dubai", repo.prefs.value!!.quietTz)
        assertEquals(JsonPrimitive("22:00"), api.patches.single()["quiet_start"])
        assertTrue(api.calls.contains("updateAlertPrefs(${TestData.USER_ID})"))
    }

    @Test
    fun `a failed write rolls back to the server's value`() = scope.runTest {
        val repo = repo()
        repo.refresh()
        api.updateFailure = BackendException.Network(IOException("down"))
        assertEquals(AlertPrefsResult.Offline, repo.update(AlertPrefsChange.Language(AlertLanguage.HI)))
        assertEquals(AlertLanguage.EN, repo.prefs.value!!.language)

        auth.nextResult = AuthResult.NotConfigured
        assertEquals(AlertPrefsResult.NotConfigured, repo.update(AlertPrefsChange.QuietHoursEnabled(true)))
        assertFalse(repo.prefs.value!!.quietHoursEnabled)
    }

    @Test
    fun `a Hindi phone switches the untouched default to Hindi, once`() = scope.runTest {
        device.language = "hi"
        val repo = repo()
        repo.applyDefaultLanguageOnce(TestData.USER_ID)
        assertEquals("hi", api.prefsRow!!.alertLanguage)
        assertEquals(AlertLanguage.HI, repo.prefs.value!!.language)

        // The user picks English later: a new sign-in must not undo it.
        api.prefsRow = api.prefsRow!!.copy(alertLanguage = "en")
        api.calls.clear()
        repo.applyDefaultLanguageOnce(TestData.USER_ID)
        assertEquals("en", api.prefsRow!!.alertLanguage)
        assertTrue("already applied: no request at all", api.calls.isEmpty())
    }

    @Test
    fun `a choice made elsewhere wins over the phone language`() = scope.runTest {
        device.language = "hi"
        api.prefsRow = AlertPrefsDto(alertLanguage = "hinglish")
        repo().applyDefaultLanguageOnce(TestData.USER_ID)
        assertEquals("hinglish", api.prefsRow!!.alertLanguage)
        assertTrue(api.patches.isEmpty())
    }

    @Test
    fun `an English phone never writes, and an offline attempt is retried next time`() = scope.runTest {
        val repo = repo()
        repo.applyDefaultLanguageOnce(TestData.USER_ID)
        assertTrue(api.patches.isEmpty())

        device.language = "hi"
        val other = "other-user"
        api.failure = BackendException.Network(IOException("down"))
        repo.applyDefaultLanguageOnce(other)
        api.failure = null
        repo.applyDefaultLanguageOnce(other)
        assertEquals("hi", api.prefsRow!!.alertLanguage)
    }

    @Test
    fun `view model shows problems until the row loads and reports failed saves once`() = scope.runTest {
        api.failure = BackendException.Network(IOException("down"))
        val repo = repo()
        val vm = AlertPrefsViewModel(repo)
        assertEquals(AlertPrefsProblem.OFFLINE, vm.uiState.value.problem)
        assertFalse(vm.uiState.value.editable)
        vm.setLanguage(AlertLanguage.HI) // ignored while nothing is loaded
        assertTrue(api.patches.isEmpty())

        api.failure = null
        vm.refresh()
        assertTrue(vm.uiState.value.editable)
        assertNull(vm.uiState.value.problem)

        vm.setQuietHoursEnabled(true)
        assertTrue(vm.uiState.value.prefs!!.quietHoursEnabled)

        api.updateFailure = BackendException.Http(500, null, "boom")
        vm.setQuietEnd(LocalTime.of(8, 0))
        assertTrue(vm.uiState.value.saveFailed)
        assertEquals(LocalTime.of(7, 0), vm.uiState.value.prefs!!.quietEnd)
        vm.saveFailedShown()
        assertFalse(vm.uiState.value.saveFailed)
    }
}
