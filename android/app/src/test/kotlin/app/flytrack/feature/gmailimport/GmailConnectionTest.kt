package app.flytrack.feature.gmailimport

import app.flytrack.testing.FakeGmailApi
import app.flytrack.testing.FakeGmailAuth
import app.flytrack.testing.FakeGmailNotifier
import app.flytrack.testing.FakeGmailSuggestionStore
import app.flytrack.testing.FakeSettingsStore
import app.flytrack.testing.FakeWorkScheduler
import app.flytrack.testing.GmailFixtures.suggestion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailConnectionTest {
    private val auth = FakeGmailAuth()
    private val api = FakeGmailApi()
    private val store = FakeGmailSuggestionStore()
    private val settings = FakeSettingsStore()
    private val notifier = FakeGmailNotifier()
    private val work = FakeWorkScheduler()
    private val connection = GmailConnection(auth, api, store, settings, notifier, work)

    @Test
    fun `connecting saves the address, clears needs reconnect and starts the background check`() = runTest {
        settings.setGmailNeedsReconnect(true)

        connection.completeConnect("token-1")

        assertEquals(listOf("token-1"), api.tokensUsed)
        assertEquals("traveller@gmail.com", settings.gmailAccountEmail.first())
        assertFalse(settings.gmailNeedsReconnect.first())
        assertEquals(1, work.scheduled)
    }

    @Test
    fun `with auto-check off connecting does not start the background check`() = runTest {
        settings.setGmailAutoCheck(false)

        connection.completeConnect("token-1")

        assertEquals(0, work.scheduled)
    }

    @Test
    fun `reconnecting the same account keeps the last check, another account starts over`() = runTest {
        settings.setGmailAccountEmail("traveller@gmail.com")
        settings.setGmailLastCheckEpochSeconds(1_700_000_000L)

        connection.completeConnect("token-1")
        assertEquals(1_700_000_000L, settings.gmailLastCheckEpochSeconds.first())

        api.profileEmail = "other@gmail.com"
        connection.completeConnect("token-2")
        assertEquals("other@gmail.com", settings.gmailAccountEmail.first())
        assertNull(settings.gmailLastCheckEpochSeconds.first())
    }

    @Test
    fun `a failed profile read saves nothing`() = runTest {
        api.fail("profile", GmailFailure.Offline)

        val error = runCatching { connection.completeConnect("token-1") }.exceptionOrNull()

        assertTrue(error is GmailApiException)
        assertNull(settings.gmailAccountEmail.first())
        assertEquals(0, work.scheduled)
    }

    @Test
    fun `disconnect revokes the stored account and forgets everything`() = runTest {
        fillState()

        connection.disconnect()

        assertEquals(listOf("traveller@gmail.com"), auth.revokedEmails)
        assertCleared()
    }

    @Test
    fun `disconnect still forgets everything when revoking fails`() = runTest {
        fillState()
        auth.revokeError = IllegalStateException("offline")

        connection.disconnect()

        assertCleared()
    }

    @Test
    fun `clearLocal forgets everything without revoking`() = runTest {
        fillState()

        connection.clearLocal()

        assertTrue(auth.revokedEmails.isEmpty())
        assertCleared()
    }

    @Test
    fun `clearLocal forgets the account before it empties the store, so a running scan cannot write into the wiped store`() = runTest {
        fillState()
        var emailWhenCleared: String? = "not cleared"
        val recording = object : GmailSuggestionStore by store {
            override suspend fun clear() {
                emailWhenCleared = settings.gmailAccountEmail.first()
                store.clear()
            }
        }

        GmailConnection(auth, api, recording, settings, notifier, work).clearLocal()

        assertNull(emailWhenCleared)
        assertCleared()
    }

    private suspend fun fillState() {
        settings.setGmailAccountEmail("traveller@gmail.com")
        settings.setGmailAutoCheck(false)
        settings.setGmailLastCheckEpochSeconds(1_700_000_000L)
        settings.setGmailNeedsReconnect(true)
        settings.setGmailLastAppOpenCheckEpochSeconds(1_700_000_100L)
        store.insert(listOf(suggestion()))
        store.markSeen(listOf("m1"))
    }

    private suspend fun assertCleared() {
        assertTrue(store.all().isEmpty())
        assertTrue(store.seenIds.isEmpty())
        assertNull(settings.gmailAccountEmail.first())
        assertTrue(settings.gmailAutoCheck.first())
        assertNull(settings.gmailLastCheckEpochSeconds.first())
        assertFalse(settings.gmailNeedsReconnect.first())
        assertNull(settings.gmailLastAppOpenCheckEpochSeconds.first())
        assertEquals(1, work.cancelled)
        assertEquals(1, notifier.cancelAllCalls)
    }
}
