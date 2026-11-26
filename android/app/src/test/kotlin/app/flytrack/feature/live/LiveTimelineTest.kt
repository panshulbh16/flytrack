package app.flytrack.feature.live

import app.flytrack.core.model.FlightStatus
import app.flytrack.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/** 6E 2341 DEL-BOM departs 12:55Z (18:25 IST), arrives 15:10Z (20:40 IST). */
class LiveTimelineTest {

    private fun at(time: String): Instant = Instant.parse("2026-11-25T${time}Z")

    private fun flight(
        status: FlightStatus = FlightStatus.SCHEDULED,
        polledAt: String = "09:00:00",
        actDep: String? = null,
        actArr: String? = null,
    ) = TestData.flight(
        status = status,
        actDep = actDep?.let(::at),
        actArr = actArr?.let(::at),
        lastPolledAt = at(polledAt),
        updatedAt = at(polledAt),
    )

    @Test fun segmentsAddUpAndPointsSitOnSegmentEdges() {
        assertEquals(LiveRules.PROGRESS_MAX, LiveSegment.entries.sumOf { it.weight })
        assertEquals(listOf(0, 200, 300, 850), LiveSegment.entries.map { it.start })
        assertEquals(listOf(200, 300, 850, 1000), LivePoint.entries.map { it.position })
    }

    @Test fun windowRunsFromThreeHoursBeforeDepartureToFortyFiveMinutesAfterArrival() {
        val window = flight().liveWindow()!!
        assertEquals(at("09:55:00"), window.windowStart)
        assertEquals(at("15:55:00"), window.windowEnd)
        assertFalse(at("09:54:59") in window)
        assertTrue(at("09:55:00") in window)
        assertTrue(at("15:54:59") in window)
        assertFalse(at("15:55:00") in window)
    }

    @Test fun cancelledAndTimelessFlightsHaveNoWindow() {
        assertNull(flight(status = FlightStatus.CANCELLED).liveWindow())
        assertNull(TestData.flight(schedArr = null).liveWindow())
        assertNull(TestData.flight(schedArr = Instant.parse("2026-11-25T12:00:00Z")).liveWindow())
        assertNull(liveProgress(flight(status = FlightStatus.CANCELLED), at("12:00:00")))
    }

    @Test fun timePositionInterpolatesInsideEachSegment() {
        val times = flight().liveTimes()!!
        assertEquals(0, timePosition(times, at("09:00:00")))
        assertEquals(100, timePosition(times, at("11:02:30")))
        assertEquals(200, timePosition(times, at("12:10:00")))
        assertEquals(300, timePosition(times, at("12:55:00")))
        assertEquals(575, timePosition(times, at("14:02:30")))
        assertEquals(850, timePosition(times, at("15:10:00")))
        assertEquals(1000, timePosition(times, at("16:30:00")))
    }

    @Test fun freshDataBeforeDepartureIsCheckIn() {
        val progress = liveProgress(flight(polledAt = "11:00:00"), at("11:02:30"))!!
        assertEquals(LiveStage.CHECK_IN, progress.stage)
        assertEquals(100, progress.position)
        assertFalse(progress.estimated)
    }

    @Test fun boardingStatusMovesTheBarToBoarding() {
        val progress = liveProgress(flight(status = FlightStatus.BOARDING, polledAt = "11:00:00"), at("11:02:30"))!!
        assertEquals(LiveStage.BOARDING, progress.stage)
        assertEquals(200, progress.position)
    }

    @Test fun freshDataThatHasNotDepartedNeverShowsTakeOff() {
        val progress = liveProgress(flight(polledAt = "13:25:00"), at("13:30:00"))!!
        assertEquals(LiveStage.BOARDING, progress.stage)
        assertEquals(299, progress.position)
        assertFalse(progress.estimated)
    }

    @Test fun freshDataInTheAirStaysBeforeLandingUntilTheDataSaysLanded() {
        val progress = liveProgress(flight(status = FlightStatus.EN_ROUTE, polledAt = "15:25:00", actDep = "12:55:00"), at("15:30:00"))!!
        assertEquals(LiveStage.AIRBORNE, progress.stage)
        assertEquals(849, progress.position)
        assertFalse(progress.estimated)
    }

    @Test fun staleDataInTheAirIsAnEstimate() {
        val progress = liveProgress(flight(status = FlightStatus.DEPARTED, polledAt = "12:58:00", actDep = "12:55:00"), at("14:02:30"))!!
        assertEquals(LiveStage.AIRBORNE, progress.stage)
        assertEquals(575, progress.position)
        assertTrue(progress.estimated)
    }

    @Test fun airplaneModeEstimateMovesOnWithoutAnyDepartureData() {
        val progress = liveProgress(flight(polledAt = "12:00:00"), at("14:02:30"))!!
        assertEquals(LiveStage.AIRBORNE, progress.stage)
        assertEquals(575, progress.position)
        assertTrue(progress.estimated)
        val pastArrival = liveProgress(flight(polledAt = "12:00:00"), at("15:20:00"))!!
        assertEquals(LiveStage.LANDED, pastArrival.stage)
        assertTrue(pastArrival.estimated)
    }

    @Test fun staleDataBeforeDepartureIsNotAnEstimate() {
        val progress = liveProgress(flight(polledAt = "09:00:00"), at("11:02:30"))!!
        assertFalse(progress.estimated)
    }

    @Test fun landedUsesTheActualArrivalAndMovesTowardsTheBelt() {
        val landed = flight(status = FlightStatus.LANDED, polledAt = "12:00:00", actDep = "12:55:00", actArr = "15:05:00")
        assertEquals(at("15:50:00"), landed.liveWindow()!!.windowEnd)
        val progress = liveProgress(landed, at("15:20:00"))!!
        assertEquals(LiveStage.LANDED, progress.stage)
        assertEquals(900, progress.position)
        assertFalse(progress.estimated)
    }

    @Test fun dataWithoutTimestampsIsStale() {
        assertTrue(TestData.flight(lastPolledAt = null, updatedAt = null).isStale(at("12:00:00")))
        assertFalse(flight(polledAt = "11:45:00").isStale(at("12:00:00")))
        assertTrue(flight(polledAt = "11:29:00").isStale(at("12:00:00")))
    }
}
