package app.flytrack.feature.live

import app.flytrack.core.model.FlightStatus
import app.flytrack.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class LiveFlightSelectorTest {

    private val now = Instant.parse("2026-11-25T12:00:00Z")
    private val everyone: (String) -> Boolean = { true }

    /** A flight departing [depIn] from [now], 2 hours long. */
    private fun tracked(
        id: String,
        depIn: kotlin.time.Duration,
        status: FlightStatus = FlightStatus.SCHEDULED,
        archived: Boolean = false,
        departed: Boolean = false,
    ) = TestData.flight(
        id = id,
        status = status,
        schedDep = now + depIn,
        schedArr = now + depIn + 2.hours,
        actDep = if (departed) now + depIn else null,
        lastPolledAt = now,
        updatedAt = now,
    ).let { TestData.tracked(it, TestData.tracking(it.id, archivedAt = if (archived) now else null)) }

    @Test fun nothingInsideTheWindowMeansNoLiveFlight() {
        assertNull(LiveFlightSelector.selectLive(listOf(tracked("a", 4.hours)), now, everyone))
        assertEquals("a", LiveFlightSelector.selectLive(listOf(tracked("a", 2.hours)), now, everyone)?.id)
    }

    @Test fun onlyProOrTrialFlightsGetTheLiveNotification() {
        val flights = listOf(tracked("a", 1.hours), tracked("b", 2.hours))
        assertEquals("b", LiveFlightSelector.selectLive(flights, now, isEligible = { it == "b" })?.id)
        assertNull(LiveFlightSelector.selectLive(flights, now, isEligible = { false }))
    }

    @Test fun archivedDismissedAndCancelledFlightsAreSkipped() {
        val flights = listOf(
            tracked("archived", 1.hours, archived = true),
            tracked("cancelled", 1.hours, status = FlightStatus.CANCELLED),
            tracked("dismissed", 1.hours),
            tracked("kept", 2.hours),
        )
        assertEquals("kept", LiveFlightSelector.selectLive(flights, now, everyone, dismissed = setOf("dismissed"))?.id)
    }

    @Test fun aFlightInTheAirBeatsTheNextDeparture() {
        val flights = listOf(
            tracked("next", 30.minutes),
            tracked("airborne", (-1).hours, status = FlightStatus.EN_ROUTE, departed = true),
        )
        assertEquals("airborne", LiveFlightSelector.selectLive(flights, now, everyone)?.id)
    }

    @Test fun theSoonestDepartureWinsAndANewDepartureBeatsALanding() {
        val landed = TestData.flight(
            id = "landed", status = FlightStatus.LANDED,
            schedDep = now - 3.hours, schedArr = now - 20.minutes, actDep = now - 3.hours, actArr = now - 20.minutes,
        ).let { TestData.tracked(it) }
        val flights = listOf(landed, tracked("later", 2.hours), tracked("sooner", 1.hours))
        assertEquals("sooner", LiveFlightSelector.selectLive(flights, now, everyone)?.id)
        assertEquals("landed", LiveFlightSelector.selectLive(listOf(landed), now, everyone)?.id)
    }

    @Test fun widgetShowsTheNextDepartureEvenDaysAheadAndForFreeUsers() {
        val flights = listOf(tracked("far", 72.hours), tracked("near", 30.hours), tracked("past", (-10).hours))
        assertEquals("near", LiveFlightSelector.selectWidget(flights, now)?.id)
        assertNull(LiveFlightSelector.selectWidget(listOf(tracked("past", (-10).hours)), now))
        assertNull(LiveFlightSelector.selectWidget(emptyList(), now))
    }

    @Test fun widgetPrefersTheCurrentFlightAndKeepsACancellationVisible() {
        val current = tracked("current", 1.hours)
        val cancelled = tracked("cancelled", (-30).minutes, status = FlightStatus.CANCELLED)
        assertEquals("current", LiveFlightSelector.selectWidget(listOf(cancelled, current), now)?.id)
        assertEquals("cancelled", LiveFlightSelector.selectWidget(listOf(cancelled, tracked("tomorrow", 24.hours)), now)?.id)
        assertNull(LiveFlightSelector.selectWidget(listOf(tracked("old", (-2).hours, status = FlightStatus.CANCELLED)), now))
    }

    @Test fun nextWindowStartIsTheEarliestFutureWindowOfAnEligibleFlight() {
        val flights = listOf(tracked("a", 10.hours), tracked("b", 5.hours), tracked("live", 1.hours))
        assertEquals(now + 2.hours, LiveFlightSelector.nextWindowStart(flights, now, everyone))
        assertEquals(now + 7.hours, LiveFlightSelector.nextWindowStart(flights, now, isEligible = { it == "a" }))
        assertNull(LiveFlightSelector.nextWindowStart(flights, now, isEligible = { false }))
    }
}
