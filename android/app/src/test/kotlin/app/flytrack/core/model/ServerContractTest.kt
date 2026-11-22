package app.flytrack.core.model

import app.flytrack.core.push.PushContract
import app.flytrack.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.days

/**
 * Keeps the app's enums and push constants identical to the backend sources in this monorepo.
 * When the backend adds a status or an alert type, these tests fail until the app knows it too.
 */
class ServerContractTest {
    // Unit tests run with the module directory (android/app) as working directory.
    private val shared = File("../../supabase/functions/_shared")

    private fun source(name: String): String {
        val file = File(shared, name)
        assumeTrue("backend sources not present next to the android project", file.exists())
        return file.readText()
    }

    private fun stringArray(source: String, constName: String): List<String> {
        val body = Regex("""const $constName[^=]*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(source)!!.groupValues[1]
        // comments may contain quotes or commas, so drop them before collecting the string literals
        return Regex(""""([^"]+)"""").findAll(body.replace(Regex("//[^\n]*"), "")).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `FlightStatus matches FLIGHT_STATUSES`() {
        assertEquals(stringArray(source("types.ts"), "FLIGHT_STATUSES"), FlightStatus.entries.map { it.wire })
    }

    @Test
    fun `AlertType matches EVENT_TYPES`() {
        assertEquals(stringArray(source("types.ts"), "EVENT_TYPES"), AlertType.entries.map { it.wire })
    }

    @Test
    fun `critical alert types match notify_ts`() {
        val critical = stringArray(source("notify.ts"), "CRITICAL").toSet()
        assertEquals(critical, AlertType.entries.filter { it.critical }.map { it.wire }.toSet())
    }

    @Test
    fun `push constants match notify_ts`() {
        val notify = source("notify.ts")
        assertTrue(notify.contains("""CHANNEL_CRITICAL = "${PushContract.CHANNEL_CRITICAL}""""))
        assertTrue(notify.contains("""CHANNEL_UPDATES = "${PushContract.CHANNEL_UPDATES}""""))
        assertTrue(notify.contains("""click_action: "${PushContract.ACTION_OPEN_FLIGHT}""""))
        assertTrue(notify.contains("""kind: "${PushContract.KIND_ALERT}", ${PushContract.KEY_FLIGHT_ID}: flightId, ${PushContract.KEY_EVENT_ID}: eventId, ${PushContract.KEY_EVENT_TYPE}: e.type, ${PushContract.KEY_TITLE}, ${PushContract.KEY_BODY}"""))
        assertTrue(notify.contains("""kind: "${PushContract.KIND_SYNC}", ${PushContract.KEY_FLIGHT_ID}: row.id, ${PushContract.KEY_FLIGHT} }"""))
    }

    @Test
    fun `unknown wire values degrade safely`() {
        assertEquals(FlightStatus.UNKNOWN, FlightStatus.fromWire("holding_pattern"))
        assertEquals(FlightStatus.UNKNOWN, FlightStatus.fromWire(null))
        assertEquals(FlightStatus.EN_ROUTE, FlightStatus.fromWire("en_route"))
        assertNull(AlertType.fromWire("SOMETHING_NEW"))
        assertEquals(AlertType.GATE_CHANGE, AlertType.fromWire("GATE_CHANGE"))
        assertEquals(Plan.FREE, Plan.fromWire("enterprise"))
        assertNull(Cabin.fromWire("suite"))
        assertEquals(TrackSource.MANUAL, TrackSource.fromWire(null))
    }

    @Test
    fun `only the inbound alert is Pro-only`() {
        assertEquals(listOf(AlertType.INBOUND_LATE), AlertType.entries.filter { it.proOnly })
    }

    @Test
    fun `profile follows the is_pro rule and the tracking limits`() {
        val now = TestData.NOW
        assertFalse(TestData.profile(plan = Plan.FREE).isPro(now))
        assertTrue("null pro_until is a lifetime grant", TestData.profile(plan = Plan.PRO, proUntil = null).isPro(now))
        assertTrue(TestData.profile(plan = Plan.PRO, proUntil = now + 1.days).isPro(now))
        assertFalse(TestData.profile(plan = Plan.PRO, proUntil = now - 1.days).isPro(now))
        assertEquals(10, TestData.profile().trackingLimit(now))
        assertEquals(50, TestData.profile(plan = Plan.PRO).trackingLimit(now))
    }

    @Test
    fun `PNR is normalised to the database rule`() {
        assertEquals("ABC123", normalizePnr("abc123"))
        assertEquals("ABC123", normalizePnr(" abc 123 "))
        assertEquals("AB12CD34", normalizePnr("ab12-cd34"))
        assertNull(normalizePnr("AB12"))
        assertNull(normalizePnr("ABCDEFGHI"))
        assertNull(normalizePnr("ABC12!"))
        assertNull(normalizePnr(null))
        assertNull(normalizePnr(""))
    }

    @Test
    fun `an alert is sent unless its preference is exactly false`() {
        val tracking = TestData.tracking(notifyPrefs = mapOf(AlertType.DEPARTED to false, AlertType.LANDED to true))
        assertFalse(tracking.notifies(AlertType.DEPARTED))
        assertTrue(tracking.notifies(AlertType.LANDED))
        assertTrue(tracking.notifies(AlertType.GATE_CHANGE))
    }
}
