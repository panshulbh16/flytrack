package app.flytrack.feature.checkin

import app.flytrack.core.push.PushContract
import app.flytrack.push.IncomingPush
import app.flytrack.push.parseIncomingPush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CheckinPushTest {
    private fun alert(eventType: String, url: String?) = mapOf(
        "kind" to "alert",
        "flight_id" to "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        "event_id" to "7",
        "event_type" to eventType,
        "title" to "Web check-in is open: 6E 2341",
        "body" to "DEL→BOM. Check in now.",
    ) + listOfNotNull(url?.let { "checkin_url" to it })

    @Test
    fun `a check-in alert carries the airline link`() {
        val push = parseIncomingPush(alert("CHECKIN_OPEN", "https://www.goindigo.in/web-check-in.html")) as IncomingPush.Alert
        assertEquals("https://www.goindigo.in/web-check-in.html", push.checkinUrl)
        assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa:checkin_open", push.tag)
    }

    @Test
    fun `check-in opening is an info alert, closing is critical (same channels as the server)`() {
        val open = parseIncomingPush(alert("CHECKIN_OPEN", "https://www.goindigo.in/web-check-in.html")) as IncomingPush.Alert
        val closing = parseIncomingPush(alert("CHECKIN_CLOSING", "https://www.goindigo.in/web-check-in.html")) as IncomingPush.Alert
        assertEquals(PushContract.CHANNEL_UPDATES, open.channelId)
        assertEquals(PushContract.CHANNEL_CRITICAL, closing.channelId)
    }

    @Test
    fun `other alerts and unsafe links carry none`() {
        assertNull((parseIncomingPush(alert("GATE_CHANGE", "https://www.goindigo.in/")) as IncomingPush.Alert).checkinUrl)
        assertNull((parseIncomingPush(alert("CHECKIN_CLOSING", "http://www.goindigo.in/")) as IncomingPush.Alert).checkinUrl)
        assertNull((parseIncomingPush(alert("CHECKIN_CLOSING", null)) as IncomingPush.Alert).checkinUrl)
    }
}
