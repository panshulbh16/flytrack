package app.flytrack.feature.gmailimport

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.flytrack.data.local.FlyTrackDatabase
import app.flytrack.data.local.RoomTransactionRunner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The DAO's SQL only runs on a device: the JVM tests have no SQLite driver. */
@RunWith(AndroidJUnit4::class)
class RoomGmailSuggestionStoreTest {
    private val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        FlyTrackDatabase::class.java,
    ).build()
    private val store = RoomGmailSuggestionStore(db.gmailDao(), RoomTransactionRunner(db))

    @After
    fun close() = db.close()

    private fun suggestion(id: String, date: LocalDate, subject: String = "Your e-ticket") = GmailSuggestion(
        id = id,
        messageId = "m-$id",
        flight = id.substringBefore('_'),
        date = date,
        fromIata = null,
        toIata = null,
        pnr = null,
        subject = subject,
        status = SuggestionStatus.PENDING,
        foundAtEpochMs = 1L,
    )

    @Test
    fun insertKeepsAStoredStatusAndListsByDate() = runBlocking {
        val later = suggestion("AI101_2026-12-20", LocalDate(2026, 12, 20))
        val sooner = suggestion("6E2341_2026-12-03", LocalDate(2026, 12, 3))
        store.insert(listOf(later, sooner))
        store.setStatus(listOf(sooner.id), SuggestionStatus.DISMISSED)

        store.insert(listOf(sooner.copy(subject = "Found again")))

        assertEquals(listOf(sooner.id, later.id), store.all().map { it.id })
        assertEquals(SuggestionStatus.DISMISSED, store.get(sooner.id)?.status)
        assertEquals("Your e-ticket", store.get(sooner.id)?.subject)
        assertEquals(store.all(), store.observeAll().first())
    }

    @Test
    fun seenReturnsOnlyMarkedIdsAndMarkingTwiceIsFine() = runBlocking {
        store.markSeen(listOf("m1", "m2"))
        store.markSeen(listOf("m2"))

        assertEquals(setOf("m1", "m2"), store.seen(listOf("m1", "m2", "m3")))
        assertTrue(store.seen(emptyList()).isEmpty())
    }

    @Test
    fun deleteRemovesRowsAndClearEmptiesBothTables() = runBlocking {
        val a = suggestion("6E2341_2026-12-03", LocalDate(2026, 12, 3))
        val b = suggestion("AI101_2026-12-20", LocalDate(2026, 12, 20))
        store.insert(listOf(a, b))
        store.markSeen(listOf("m1"))

        store.delete(listOf(a.id))
        assertNull(store.get(a.id))
        assertEquals(listOf(b.id), store.all().map { it.id })

        store.clear()
        assertTrue(store.all().isEmpty())
        assertTrue(store.seen(listOf("m1")).isEmpty())
    }
}
