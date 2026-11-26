package app.flytrack.feature.flights.presentation

import app.flytrack.core.estimate.PositionSource
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.LivePosition
import app.flytrack.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The card's progress line comes from core/estimate (docs/P2_CONTRACTS.md section 5). */
class FlightCardEstimateTest {
    private val schedDep = Instant.parse("2026-11-25T12:55:00Z")
    private val schedArr = Instant.parse("2026-11-25T15:10:00Z")

    @Test
    fun `in the air without a live fix the progress is estimated`() {
        val card = TestData.flight(status = FlightStatus.EN_ROUTE, actDep = schedDep).toCardUiModel(schedDep + 1.hours)

        assertEquals(60f / 135f, card.progress!!, 0.001f)
        assertEquals(PositionSource.ESTIMATED, card.progressSource)
        assertEquals(schedArr, card.progressArrival)
    }

    @Test
    fun `a fresh live fix makes it live`() {
        val now = schedDep + 1.hours
        val fix = LivePosition(23.5, 74.6, 35000, 200.0, 440.0, now - 5.minutes)
        val card = TestData.flight(status = FlightStatus.EN_ROUTE, actDep = schedDep, live = fix).toCardUiModel(now)

        assertEquals(PositionSource.LIVE, card.progressSource)
    }

    @Test
    fun `offline past departure the card still moves, marked estimated`() {
        val lastSeen = schedDep - 3.hours
        val card = TestData.flight(lastPolledAt = lastSeen, updatedAt = lastSeen).toCardUiModel(schedDep + 30.minutes)

        assertEquals(30f / 135f, card.progress!!, 0.001f)
        assertEquals(PositionSource.ESTIMATED, card.progressSource)
    }

    @Test
    fun `no progress and no source before departure`() {
        val card = TestData.flight().toCardUiModel(schedDep - 1.hours)

        assertNull(card.progress)
        assertNull(card.progressSource)
        assertNull(card.progressArrival)
    }
}
