package app.flytrack.feature.gmailimport

import app.flytrack.feature.importticket.ImportToday
import app.flytrack.testing.FakeClock
import app.flytrack.testing.FakeFlightRepository
import app.flytrack.testing.FakeGmailApi
import app.flytrack.testing.FakeGmailAuth
import app.flytrack.testing.FakeGmailNotifier
import app.flytrack.testing.FakeGmailSuggestionStore
import app.flytrack.testing.FakeSettingsStore
import app.flytrack.testing.GmailFixtures.pdfMessage
import app.flytrack.testing.GmailFixtures.suggestion
import app.flytrack.testing.GmailFixtures.textMessage
import app.flytrack.testing.GmailFixtures.ticket
import app.flytrack.testing.TestData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class GmailScannerTest {
    private val today = LocalDate(2026, 11, 22)
    private val clock = FakeClock()
    private val api = FakeGmailApi()
    private val auth = FakeGmailAuth()
    private val store = FakeGmailSuggestionStore()
    private val settings = FakeSettingsStore()
    private var trackedKeys = emptySet<TrackedKey>()
    private val pdfReads = mutableListOf<ByteArray>()
    private var pdfFailure: Exception? = null

    private val scanner = GmailScanner(
        api = api,
        auth = auth,
        store = store,
        settings = settings,
        tracked = object : TrackedKeysSource {
            override suspend fun current(): Set<TrackedKey> = trackedKeys
        },
        pdf = object : PdfTextReader {
            override suspend fun read(bytes: ByteArray): String {
                pdfFailure?.let { throw it }
                pdfReads += bytes
                return bytes.decodeToString()
            }
        },
        today = ImportToday { today },
        clock = clock,
    )

    private suspend fun connected() = settings.setGmailAccountEmail(EMAIL)

    private fun inbox(vararg messages: GmailMessage) {
        messages.forEach { api.messages[it.id] = it }
        api.pages = listOf(messages.map { it.id })
    }

    @Test
    fun `not connected does nothing`() = runTest {
        assertEquals(ScanOutcome(ScanResult.NotConnected, emptyList()), scanner.scan())
        assertTrue(auth.silentEmails.isEmpty())
        assertTrue(api.listCalls.isEmpty())
    }

    @Test
    fun `a token that needs the user marks needs reconnect without calling gmail`() = runTest {
        connected()
        auth.silentResults = listOf(GmailAuthResult.NeedsUser(null))

        assertEquals(ScanOutcome(ScanResult.NeedsReconnect, emptyList()), scanner.scan())
        assertTrue(settings.gmailNeedsReconnect.first())
        assertTrue(api.listCalls.isEmpty())
    }

    @Test
    fun `no network for the token is offline`() = runTest {
        connected()
        auth.silentResults = listOf(GmailAuthResult.Offline)

        assertEquals(ScanResult.Offline, scanner.scan().result)
        assertNull(settings.gmailLastCheckEpochSeconds.first())
    }

    @Test
    fun `a completed first scan stores new suggestions, marks mail seen and saves the start time`() = runTest {
        connected()
        inbox(
            textMessage("m1", "Your Air India booking", ticket()),
            textMessage("m2", "Booking confirmation", "Thanks for booking with us."),
        )
        api.onCall = { clock.advance(1.minutes) }

        val outcome = scanner.scan()

        assertEquals(ScanResult.Completed, outcome.result)
        assertEquals(listOf(EMAIL), auth.silentEmails)
        assertEquals(listOf(GmailQuery.build(null)), api.listCalls.map { it.query })
        val found = outcome.newSuggestions.single()
        assertEquals("AI101_2026-11-25", found.id)
        assertEquals("m1", found.messageId)
        assertEquals("AI 101", found.flight)
        assertEquals(LocalDate(2026, 11, 25), found.date)
        assertEquals("DEL", found.fromIata)
        assertEquals("BOM", found.toIata)
        assertEquals("A1B2C3", found.pnr)
        assertEquals("Your Air India booking", found.subject)
        assertEquals(SuggestionStatus.PENDING, found.status)
        assertEquals(listOf(found), store.all())
        assertEquals(setOf("m1", "m2"), store.seenIds)
        // The start time, not the end: mail that arrived while scanning is listed next time.
        assertEquals(TestData.NOW.epochSeconds, settings.gmailLastCheckEpochSeconds.first())
    }

    @Test
    fun `a later scan asks for mail after the last check and skips seen mail unread`() = runTest {
        connected()
        settings.setGmailLastCheckEpochSeconds(1_700_000_000L)
        store.markSeen(listOf("m1"))
        inbox(
            textMessage("m1", "Booking", ticket("AI-101")),
            textMessage("m2", "Booking", ticket("AI-102")),
        )

        val outcome = scanner.scan()

        assertEquals(GmailQuery.build(1_700_000_000L), api.listCalls.single().query)
        assertEquals(listOf("m2"), api.messageCalls)
        assertEquals(listOf("AI102_2026-11-25"), outcome.newSuggestions.map { it.id })
    }

    @Test
    fun `every page of the listing is read`() = runTest {
        connected()
        api.messages["m1"] = textMessage("m1", "Booking", ticket("AI-101"))
        api.messages["m2"] = textMessage("m2", "Booking", ticket("AI-102"))
        api.pages = listOf(listOf("m1"), listOf("m2"))

        val outcome = scanner.scan()

        assertEquals(ScanResult.Completed, outcome.result)
        assertEquals(listOf(null, "page-1"), api.listCalls.map { it.pageToken })
        assertEquals(listOf("m1", "m2"), api.messageCalls)
        assertEquals(2, outcome.newSuggestions.size)
    }

    @Test
    fun `flights already tracked or already suggested are not suggested again`() = runTest {
        connected()
        trackedKeys = setOf(TrackedKey("AI101", LocalDate(2026, 11, 25)))
        store.insert(listOf(suggestion(flight = "AI 102", status = SuggestionStatus.DISMISSED)))
        inbox(
            textMessage("m1", "Booking", ticket("AI-101")),
            textMessage("m2", "Booking", ticket("AI-102")),
            textMessage("m3", "Booking", ticket("AI-103")),
        )

        val outcome = scanner.scan()

        assertEquals(listOf("AI103_2026-11-25"), outcome.newSuggestions.map { it.id })
        assertEquals(SuggestionStatus.DISMISSED, store.get("AI102_2026-11-25")?.status)
        assertNull(store.get("AI101_2026-11-25"))
        assertEquals(setOf("m1", "m2", "m3"), store.seenIds)
    }

    @Test
    fun `duplicate legs keep the newest email's pnr and subject`() = runTest {
        connected()
        inbox( // Gmail lists newest first
            textMessage("m-new", "Changed booking", ticket(pnr = "NEW222")).copy(internalDate = "1795000000000"),
            textMessage("m-old", "Booking", ticket(pnr = "OLD111")).copy(internalDate = "1794000000000"),
        )
        api.onCall = { clock.advance(1.minutes) } // the older email is read later

        val found = scanner.scan().newSuggestions.single()

        assertEquals("m-new", found.messageId)
        assertEquals("NEW222", found.pnr)
        assertEquals("Changed booking", found.subject)
        assertEquals(setOf("m-new", "m-old"), store.seenIds)
    }

    @Test
    fun `duplicate legs keep the newest email even when the inbox lists the oldest first`() = runTest {
        connected()
        inbox(
            textMessage("m-old", "Booking", ticket(pnr = "OLD111")).copy(internalDate = "1794000000000"),
            textMessage("m-new", "Changed booking", ticket(pnr = "NEW222")).copy(internalDate = "1795000000000"),
        )
        api.onCall = { clock.advance(1.minutes) } // the newer email is read later, but its own date decides

        val found = scanner.scan().newSuggestions.single()

        assertEquals("m-new", found.messageId)
        assertEquals("NEW222", found.pnr)
        assertEquals(1_795_000_000_000L, found.foundAtEpochMs)
        assertEquals(setOf("m-new", "m-old"), store.seenIds)
    }

    @Test
    fun `more than 50 unseen emails stop at the cap without saving the last check`() = runTest {
        connected()
        val ids = (1..51).map { "m$it" }
        ids.forEach { api.messages[it] = textMessage(it, "Booking", "Nothing here") }
        api.pages = listOf(ids.take(30), ids.drop(30))

        val outcome = scanner.scan()

        assertEquals(ScanResult.Partial, outcome.result)
        assertEquals(ids.take(50), api.messageCalls)
        assertEquals(ids.take(50).toSet(), store.seenIds)
        assertNull(settings.gmailLastCheckEpochSeconds.first())
    }

    @Test
    fun `seen emails do not count toward the cap`() = runTest {
        connected()
        val ids = (1..60).map { "m$it" }
        ids.forEach { api.messages[it] = textMessage(it, "Booking", "Nothing here") }
        store.markSeen(ids.take(10))
        api.pages = listOf(ids)

        assertEquals(ScanResult.Completed, scanner.scan().result)
        assertEquals(ids.drop(10), api.messageCalls)
    }

    @Test
    fun `a mailbox over the cap is finished over several scans and only the last one saves the check`() = runTest {
        connected()
        val ids = (1..120).map { "m$it" }
        ids.forEach { api.messages[it] = textMessage(it, "Booking", "Nothing here") }
        api.messages["m120"] = textMessage("m120", "Booking", ticket("AI-120"))
        api.pages = ids.chunked(100)

        assertEquals(ScanResult.Partial, scanner.scan().result) // m1..m50
        assertEquals(ScanResult.Partial, scanner.scan().result) // m51..m100
        assertNull(settings.gmailLastCheckEpochSeconds.first())
        clock.advance(1.minutes)
        val last = scanner.scan() // m101..m120

        assertEquals(ScanResult.Completed, last.result)
        assertEquals(listOf("AI120_2026-11-25"), last.newSuggestions.map { it.id })
        assertEquals(ids, api.messageCalls) // every email read exactly once
        assertEquals(listOf(GmailQuery.build(null)), api.listCalls.map { it.query }.distinct())
        val savedAt = (TestData.NOW + 1.minutes).epochSeconds
        assertEquals(savedAt, settings.gmailLastCheckEpochSeconds.first())

        scanner.scan()
        assertEquals(GmailQuery.build(savedAt), api.listCalls.last().query)
    }

    @Test
    fun `disconnecting while a scan runs leaves nothing behind`() = runTest {
        connected()
        inbox(
            textMessage("m1", "Booking", ticket("AI-101")),
            textMessage("m2", "Booking", ticket("AI-102")),
        )
        api.onCall = {
            // Disconnect Gmail (or HeyGate sign-out) lands while m2 is being fetched. The fakes never suspend.
            if (api.messageCalls == listOf("m1", "m2")) {
                runBlocking {
                    store.clear()
                    settings.setGmailAccountEmail(null)
                }
            }
        }

        assertEquals(ScanOutcome(ScanResult.NotConnected, emptyList()), scanner.scan())
        assertTrue(store.all().isEmpty())
        assertTrue(store.seenIds.isEmpty())
        assertNull(settings.gmailLastCheckEpochSeconds.first())
        assertFalse(settings.gmailNeedsReconnect.first())
    }

    @Test
    fun `a date without a year is read against the email's date, so an old trip is not moved to next year`() = runTest {
        connected()
        inbox(
            // Received 20 Nov 2026, for a trip on 3 Dec.
            textMessage("m-new", "Booking", ticket("AI-102", date = "3 Dec")).copy(internalDate = "1795176000000"),
            // Received 10 Jun 2026, for a trip on 15 Jul. Read against today (22 Nov 2026), that would be 15 Jul 2027.
            textMessage("m-old", "Booking", ticket("AI-101", date = "15 Jul")).copy(internalDate = "1781092800000"),
        )

        val outcome = scanner.scan()

        assertEquals(listOf("AI102_2026-12-03"), outcome.newSuggestions.map { it.id })
        assertEquals(setOf("m-new", "m-old"), store.seenIds)
    }

    @Test
    fun `a cancellation email is skipped and marked seen`() = runTest {
        connected()
        inbox(textMessage("m1", "Booking CANCELLED - refund initiated", ticket()))

        val outcome = scanner.scan()

        assertEquals(ScanOutcome(ScanResult.Completed, emptyList()), outcome)
        assertEquals(setOf("m1"), store.seenIds)
    }

    @Test
    fun `an email that cannot be read is skipped and marked seen`() = runTest {
        connected()
        api.messages["m1"] = GmailMessage("m1", payload = null)
        api.messages["m2"] = textMessage("m2", "Booking", ticket())
        api.pages = listOf(listOf("m1", "m2", "m3"))
        api.fail("message:m3", GmailFailure.Other) // deleted since it was listed

        val outcome = scanner.scan()

        assertEquals(ScanResult.Completed, outcome.result)
        assertEquals(listOf("AI101_2026-11-25"), outcome.newSuggestions.map { it.id })
        assertEquals(setOf("m1", "m2", "m3"), store.seenIds)
    }

    @Test
    fun `an email without a flight in its body is read from its pdf ticket`() = runTest {
        connected()
        inbox(pdfMessage("m1", "Your e-ticket", attachmentId = "att-1", size = 40_000))
        api.attachments["att-1"] = ticket("AI-101").encodeToByteArray()

        val outcome = scanner.scan()

        assertEquals(listOf("att-1"), api.attachmentCalls)
        assertEquals(1, pdfReads.size)
        assertEquals(listOf("AI101_2026-11-25"), outcome.newSuggestions.map { it.id })
    }

    @Test
    fun `a pdf over 5 MB is not downloaded`() = runTest {
        connected()
        inbox(pdfMessage("m1", "Your e-ticket", attachmentId = "att-1", size = 5_000_001))

        val outcome = scanner.scan()

        assertEquals(ScanOutcome(ScanResult.Completed, emptyList()), outcome)
        assertTrue(api.attachmentCalls.isEmpty())
        assertEquals(setOf("m1"), store.seenIds)
    }

    @Test
    fun `the pdf is not downloaded when the body already names a flight`() = runTest {
        connected()
        inbox(pdfMessage("m1", "Your e-ticket", attachmentId = "att-1", size = 40_000, body = ticket()))

        val outcome = scanner.scan()

        assertTrue(api.attachmentCalls.isEmpty())
        assertEquals(1, outcome.newSuggestions.size)
    }

    @Test
    fun `a pdf that cannot be read is skipped and marked seen`() = runTest {
        connected()
        inbox(pdfMessage("m1", "Your e-ticket", attachmentId = "att-1", size = 40_000))
        api.attachments["att-1"] = byteArrayOf(1, 2, 3)
        pdfFailure = IllegalStateException("broken pdf")

        assertEquals(ScanOutcome(ScanResult.Completed, emptyList()), scanner.scan())
        assertEquals(setOf("m1"), store.seenIds)
    }

    @Test
    fun `a 401 gets one fresh token and retries`() = runTest {
        connected()
        auth.silentResults = listOf(GmailAuthResult.Granted("token-1"), GmailAuthResult.Granted("token-2"))
        api.acceptedToken = "token-2"
        inbox(textMessage("m1", "Booking", ticket()))

        val outcome = scanner.scan()

        assertEquals(ScanResult.Completed, outcome.result)
        assertEquals(1, outcome.newSuggestions.size)
        assertEquals(listOf("token-1"), auth.clearedTokens)
        assertEquals(listOf("token-1", "token-2", "token-2"), api.tokensUsed)
        assertFalse(settings.gmailNeedsReconnect.first())
    }

    @Test
    fun `a second 401 means access was lost`() = runTest {
        connected()
        auth.silentResults = listOf(GmailAuthResult.Granted("token-1"), GmailAuthResult.Granted("token-2"))
        api.acceptedToken = "token-3"
        inbox(textMessage("m1", "Booking", ticket()))

        val outcome = scanner.scan()

        assertEquals(ScanOutcome(ScanResult.NeedsReconnect, emptyList()), outcome)
        assertEquals(listOf("token-1"), auth.clearedTokens)
        assertEquals(2, auth.silentEmails.size)
        assertTrue(settings.gmailNeedsReconnect.first())
        assertNull(settings.gmailLastCheckEpochSeconds.first())
    }

    @Test
    fun `a failed token refresh after a 401 stops the scan without losing the email or asking to reconnect`() = runTest {
        connected()
        auth.silentResults = listOf(GmailAuthResult.Granted("token-1"), GmailAuthResult.Failed)
        inbox(
            textMessage("m1", "Booking", ticket("AI-101")),
            textMessage("m2", "Booking", ticket("AI-102")),
        )
        api.fail("message:m1", GmailFailure.Unauthorized)

        val outcome = scanner.scan()

        assertEquals(ScanOutcome(ScanResult.Partial, emptyList()), outcome)
        assertEquals(listOf("m1"), api.messageCalls) // the scan stopped at m1
        assertTrue(store.seenIds.isEmpty()) // m1 is read again next time
        assertFalse(settings.gmailNeedsReconnect.first())
        assertNull(settings.gmailLastCheckEpochSeconds.first())
    }

    @Test
    fun `disconnecting while the token is fetched does not mark needs reconnect`() = runTest {
        connected()
        auth.silentResults = listOf(GmailAuthResult.NeedsUser(null))
        auth.onSilentToken = { settings.setGmailAccountEmail(null) }

        assertEquals(ScanOutcome(ScanResult.NotConnected, emptyList()), scanner.scan())
        assertFalse(settings.gmailNeedsReconnect.first())
    }

    @Test
    fun `lost access stops the scan and marks needs reconnect`() = runTest {
        connected()
        inbox(textMessage("m1", "Booking", ticket()))
        api.fail("list", GmailFailure.AccessLost)

        assertEquals(ScanResult.NeedsReconnect, scanner.scan().result)
        assertTrue(settings.gmailNeedsReconnect.first())
        assertTrue(api.messageCalls.isEmpty())
    }

    @Test
    fun `a rate limit stops the scan but keeps what was found`() = runTest {
        connected()
        inbox(
            textMessage("m1", "Booking", ticket("AI-101")),
            textMessage("m2", "Booking", ticket("AI-102")),
        )
        api.fail("message:m2", GmailFailure.RateLimited)

        val outcome = scanner.scan()

        assertEquals(ScanResult.Partial, outcome.result)
        assertEquals(listOf("AI101_2026-11-25"), outcome.newSuggestions.map { it.id })
        assertEquals(listOf("AI101_2026-11-25"), store.all().map { it.id })
        assertEquals(setOf("m1"), store.seenIds)
        assertNull(settings.gmailLastCheckEpochSeconds.first())
    }

    @Test
    fun `offline stops the scan without saving the last check`() = runTest {
        connected()
        inbox(textMessage("m1", "Booking", ticket()))
        api.fail("list", GmailFailure.Offline)

        assertEquals(ScanOutcome(ScanResult.Offline, emptyList()), scanner.scan())
        assertTrue(store.seenIds.isEmpty())
        assertNull(settings.gmailLastCheckEpochSeconds.first())
        assertFalse(settings.gmailNeedsReconnect.first())
    }

    @Test
    fun `cleanup deletes past suggestions, marks tracked ones added and cancels their notifications`() = runTest {
        trackedKeys = setOf(TrackedKey("AI102", LocalDate(2026, 11, 25)))
        store.insert(
            listOf(
                suggestion(flight = "AI 101", date = LocalDate(2026, 11, 21), status = SuggestionStatus.DISMISSED),
                suggestion(flight = "AI 102", date = LocalDate(2026, 11, 25)),
                suggestion(flight = "AI 103", date = LocalDate(2026, 11, 25)),
            ),
        )
        val notifier = FakeGmailNotifier()

        scanner.cleanup(notifier)

        assertNull(store.get("AI101_2026-11-21"))
        assertEquals(SuggestionStatus.ADDED, store.get("AI102_2026-11-25")?.status)
        assertEquals(SuggestionStatus.PENDING, store.get("AI103_2026-11-25")?.status)
        assertEquals(setOf("AI101_2026-11-21", "AI102_2026-11-25"), notifier.cancelled.toSet())
    }

    @Test
    fun `cleanup without a notifier still cleans the store`() = runTest {
        store.insert(listOf(suggestion(date = LocalDate(2026, 11, 21))))

        scanner.cleanup(null)

        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `tracked keys are the compact number and local departure date of tracked, unarchived flights`() = runTest {
        val flights = FakeFlightRepository()
        flights.setTracked(
            TestData.tracked(),
            TestData.tracked(
                flight = TestData.flight(
                    id = TestData.OTHER_FLIGHT_ID,
                    carrierIata = "AI",
                    flightNumber = "101",
                    flightDate = LocalDate(2026, 12, 3),
                ),
                tracking = TestData.tracking(flightId = TestData.OTHER_FLIGHT_ID, archivedAt = TestData.NOW),
            ),
        )

        assertEquals(
            setOf(TrackedKey("6E2341", LocalDate(2026, 11, 25))),
            RepositoryTrackedKeysSource(flights).current(),
        )
    }

    private companion object {
        const val EMAIL = "traveller@gmail.com"
    }
}
