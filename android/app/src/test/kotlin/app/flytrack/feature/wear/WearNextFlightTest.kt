package app.flytrack.feature.wear

import app.flytrack.core.model.FlightStatus
import app.flytrack.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

class WearNextFlightTest {

    private fun at(day: String, time: String): Instant = Instant.parse("2026-11-${day}T${time}Z")

    @Test fun noTrackedFlightPublishesNothing() {
        assertNull(WearNextFlight.select(emptyList(), at("25", "10:00:00")))
    }

    @Test fun archivedFlightsAreNotPublished() {
        val tracked = TestData.tracked(tracking = TestData.tracking(archivedAt = TestData.NOW))
        assertNull(WearNextFlight.select(listOf(tracked), at("25", "10:00:00")))
    }

    @Test fun upcomingFlightCarriesTheCompactShapeTimeZonesAndStatusWord() {
        val flight = TestData.flight(depGate = "42B", lastPolledAt = at("25", "10:00:00"))
        val payload = WearNextFlight.select(listOf(TestData.tracked(flight)), at("25", "10:50:00"))!!
        assertEquals(TestData.FLIGHT_ID, payload.id)
        assertEquals("6E", payload.c)
        assertEquals("2341", payload.n)
        assertEquals("2026-11-25", payload.d)
        assertEquals("DEL", payload.dep)
        assertEquals("BOM", payload.arr)
        assertEquals("scheduled", payload.st)
        assertEquals("2026-11-25T12:55:00Z", payload.sd)
        assertEquals("42B", payload.dg)
        assertEquals("1", payload.dt)
        assertEquals("Asia/Kolkata", payload.dtz)
        assertEquals("on_time", payload.sw)
        assertEquals("on_time", payload.tone)
        assertFalse(payload.est)
        assertEquals(1, payload.v)
    }

    @Test fun delayedFlightSendsTheDelayMinutes() {
        val flight = TestData.flight(
            estDep = Instant.parse("2026-11-25T13:20:00Z"),
            lastPolledAt = at("25", "10:00:00"),
        )
        val payload = WearNextFlight.select(listOf(TestData.tracked(flight)), at("25", "10:50:00"))!!
        assertEquals("delayed", payload.sw)
        assertEquals(25, payload.dm)
        assertEquals("delayed", payload.tone)
    }

    @Test fun cancelledFlightIsSentAsCancelled() {
        val flight = TestData.flight(status = FlightStatus.CANCELLED, lastPolledAt = at("25", "10:00:00"))
        val payload = WearNextFlight.select(listOf(TestData.tracked(flight)), at("25", "10:50:00"))!!
        assertEquals("cancelled", payload.sw)
        assertEquals("cancelled", payload.tone)
    }

    @Test fun theWatchFollowsTheWidgetsFlight() {
        val later = TestData.flight(
            id = TestData.OTHER_FLIGHT_ID,
            flightNumber = "5001",
            schedDep = Instant.parse("2026-11-27T12:55:00Z"),
            schedArr = Instant.parse("2026-11-27T15:10:00Z"),
        )
        val payload = WearNextFlight.select(listOf(TestData.tracked(later), TestData.tracked()), at("24", "10:00:00"))!!
        assertEquals(TestData.FLIGHT_ID, payload.id)
    }

    @Test fun encodingLeavesOutNullsAndMatchesTheWatchContract() {
        val flight = TestData.flight(depGate = "42B", lastPolledAt = at("25", "10:00:00"))
        val payload = WearNextFlight.select(listOf(TestData.tracked(flight)), at("25", "10:50:00"))!!
        val json = WearNextFlight.encode(payload).decodeToString()
        // The same string is decoded by :wear's NextFlightPayloadTest: change both together.
        assertEquals(GOLDEN_JSON, json)
        assertEquals(payload, WearNextFlight.decode(json.encodeToByteArray()))
    }

    @Test fun gateWritesTheFirstStateAndThenOnlyChanges() {
        val gate = WearPublishGate()
        val a = WearNextFlight.select(listOf(TestData.tracked()), at("24", "10:00:00"))!!
        assertEquals(WearPublishAction.Delete, gate.decide(null))
        gate.committed(null)
        assertEquals(WearPublishAction.Skip, gate.decide(null))
        assertEquals(WearPublishAction.Put(a), gate.decide(a))
        gate.committed(a)
        assertEquals(WearPublishAction.Skip, gate.decide(a.copy()))
        val changed = a.copy(dg = "7")
        assertEquals(WearPublishAction.Put(changed), gate.decide(changed))
        assertEquals(WearPublishAction.Delete, gate.decide(null))
    }

    @Test fun aFailedWriteIsRetriedOnTheNextUpdate() {
        val gate = WearPublishGate()
        val a = WearNextFlight.select(listOf(TestData.tracked()), at("24", "10:00:00"))!!
        assertEquals(WearPublishAction.Put(a), gate.decide(a))
        // No committed(): the Data Layer call failed.
        assertEquals(WearPublishAction.Put(a), gate.decide(a))
        gate.committed(a)
        gate.reset()
        assertTrue(gate.decide(a) is WearPublishAction.Put)
    }

    companion object {
        const val GOLDEN_JSON =
            """{"v":1,"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","c":"6E","n":"2341","d":"2026-11-25","dep":"DEL","arr":"BOM",""" +
                """"st":"scheduled","sd":"2026-11-25T12:55:00Z","sa":"2026-11-25T15:10:00Z","dt":"1","dg":"42B","at":"2",""" +
                """"dtz":"Asia/Kolkata","atz":"Asia/Kolkata","sw":"on_time","tone":"on_time","est":false}"""
    }
}
