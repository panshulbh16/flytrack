package app.flytrack.push

import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendException
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.FakeBackendApi
import app.flytrack.testing.FakeClock
import app.flytrack.testing.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PushAckTest {

    @Test
    fun `event ids are positive digit strings of at most 15 digits, like ack-push accepts`() {
        assertEquals(123L, parsePushEventId("123"))
        assertEquals(123L, parsePushEventId(" 123 "))
        assertEquals(999_999_999_999_999L, parsePushEventId("999999999999999"))
        assertNull(parsePushEventId("1000000000000000"))
        assertNull(parsePushEventId("0"))
        assertNull(parsePushEventId("-5"))
        assertNull(parsePushEventId("12a"))
        assertNull(parsePushEventId(""))
        assertNull(parsePushEventId(null))
    }

    @Test
    fun `request body matches the contract`() {
        val body = Json.parseToJsonElement(PushAckRequest(42, Instant.parse("2026-11-22T10:00:00Z")).toJson()).jsonObject
        assertEquals(42L, body["event_id"]!!.jsonPrimitive.long)
        assertEquals("2026-11-22T10:00:00Z", body["received_at"]!!.jsonPrimitive.content)
        assertEquals(setOf("event_id", "received_at"), body.keys)
    }

    @Test
    fun `each event is acknowledged once and the oldest ids are forgotten`() {
        val acked = AckedEvents(capacity = 2)
        assertTrue(acked.firstTime(1))
        assertFalse(acked.firstTime(1))
        assertTrue(acked.firstTime(2))
        assertTrue(acked.firstTime(3)) // forgets 1
        assertTrue(acked.firstTime(1))
    }

    @Test
    fun `arrival and open send one receipt, without blocking and ignoring failures`() = runTest {
        val backend = FakeBackendApi()
        val bodies = mutableListOf<String>()
        backend.functionHandlers["ack-push"] = { body -> bodies += body; """{"ok":true,"updated":1}""" }
        val sender = BackendPushAckSender(backend, FakeAuthRepository(), FakeClock(), this + UnconfinedTestDispatcher(testScheduler))

        sender.ack("77")
        sender.ack("77") // opened after it arrived
        sender.ack("not-an-id")
        sender.ack(null)
        testScheduler.advanceUntilIdle()

        assertEquals(1, bodies.size)
        val body = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals(77L, body["event_id"]!!.jsonPrimitive.long)
        assertEquals(TestData.NOW.toString(), body["received_at"]!!.jsonPrimitive.content)
    }

    @Test
    fun `failures and missing sessions are swallowed`() = runTest {
        val backend = FakeBackendApi().apply { failure = BackendException.Http(500, null, "boom") }
        val auth = FakeAuthRepository()
        val sender = BackendPushAckSender(backend, auth, FakeClock(), this + UnconfinedTestDispatcher(testScheduler))
        sender.send(PushAckRequest(1, TestData.NOW)) // must not throw

        auth.nextResult = AuthResult.Offline
        sender.send(PushAckRequest(2, TestData.NOW))
        assertFalse("no request without a session", backend.calls.any { it.contains("\"event_id\":2") })
    }

    private operator fun kotlinx.coroutines.CoroutineScope.plus(context: kotlin.coroutines.CoroutineContext) =
        kotlinx.coroutines.CoroutineScope(coroutineContext + context)
}
