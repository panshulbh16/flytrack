package app.flytrack.push

import app.flytrack.core.model.AlertType
import app.flytrack.core.push.PushContract
import app.flytrack.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingPushTest {
    private val json = Json.parseToJsonElement(Fixtures.read("fcm_messages.json")).jsonObject

    private fun map(name: String): Map<String, String> =
        json[name]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }

    @Test
    fun `sync fixture is a sync message with the compact flight`() {
        val parsed = parseIncomingPush(map("sync"))
        val sync = parsed as IncomingPush.Sync
        assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", sync.flightId)
        assertTrue(sync.flightJson!!.contains("\"dg\":\"31\""))
    }

    @Test
    fun `alert fixture uses the critical channel and the server tag`() {
        val parsed = parseIncomingPush(map("alert")) as IncomingPush.Alert
        val notification = json["alertNotification"]!!.jsonObject
        assertEquals(notification["channel_id"]!!.jsonPrimitive.content, parsed.channelId)
        assertEquals(notification["tag"]!!.jsonPrimitive.content, parsed.tag)
        assertEquals("Gate change: 6E 2341 → Gate 31 (T1)", parsed.title)
        assertEquals(AlertType.GATE_CHANGE, parsed.eventType)
        assertEquals(notificationIdFor(parsed.tag), parsed.notificationId)
    }

    @Test
    fun `every alert type lands on the channel and tag notify ts uses`() {
        val critical = setOf(
            "GATE_CHANGE", "TERMINAL_CHANGE", "BOARDING", "BELT_ASSIGNED", "BELT_CHANGE",
            "DELAY", "SCHEDULE_CHANGE", "CANCELLED", "DIVERTED", "REINSTATED", "INBOUND_LATE",
        )
        val tags = mapOf(
            "TERMINAL_CHANGE" to "terminal",
            "GATE_CHANGE" to "gate",
            "GATE_ASSIGNED" to "gate",
            "BELT_ASSIGNED" to "belt",
            "BELT_CHANGE" to "belt",
            "DELAY" to "delay",
            "DELAY_REDUCED" to "delay",
            "SCHEDULE_CHANGE" to "delay",
            "INBOUND_LATE" to "delay",
            "BOARDING" to "boarding",
            "DEPARTED" to "departed",
            "LANDED" to "landed",
            "CANCELLED" to "cancelled",
            "DIVERTED" to "diverted",
            "REINSTATED" to "reinstated",
        )
        assertEquals(AlertType.entries.map { it.wire }.toSet(), tags.keys)
        for (type in AlertType.entries) {
            val parsed = parseIncomingPush(
                mapOf(
                    "kind" to "alert",
                    "flight_id" to "flight-1",
                    "event_type" to type.wire,
                    "title" to "t",
                    "body" to "b",
                ),
            ) as IncomingPush.Alert
            assertEquals(type.wire, if (type.wire in critical) PushContract.CHANNEL_CRITICAL else PushContract.CHANNEL_UPDATES, parsed.channelId)
            assertEquals("flight-1:${tags.getValue(type.wire)}", parsed.tag)
        }
    }

    @Test
    fun `an alert type this app does not know yet is still shown on the critical channel`() {
        val parsed = parseIncomingPush(
            mapOf("kind" to "alert", "flight_id" to "flight-1", "event_type" to "GATE_SOMETHING", "title" to "New gate"),
        ) as IncomingPush.Alert
        assertEquals(null, parsed.eventType)
        assertEquals(PushContract.CHANNEL_CRITICAL, parsed.channelId)
        assertEquals("flight-1:gate", parsed.tag)
    }

    @Test
    fun `a message without a flight id is ignored`() {
        assertEquals(IncomingPush.Ignored, parseIncomingPush(mapOf("kind" to "sync")))
        assertEquals(IncomingPush.Ignored, parseIncomingPush(mapOf("kind" to "alert", "title" to "x")))
        assertEquals(IncomingPush.Ignored, parseIncomingPush(mapOf("kind" to "other", "flight_id" to "f")))
    }

    @Test
    fun `notification ids are stable and stay positive`() {
        val tag = "flight-1:gate"
        assertEquals(notificationIdFor(tag), notificationIdFor(tag))
        assertTrue(notificationIdFor(tag) > 0)
        assertTrue(notificationIdFor(tag) != notificationIdFor("flight-1:belt"))
    }

    @Test
    fun `a compact flight that would not fit in work data is dropped`() {
        assertEquals(null, syncPayload("x".repeat(MAX_SYNC_PAYLOAD_CHARS + 1)))
        assertEquals("{}", syncPayload("{}"))
    }
}
