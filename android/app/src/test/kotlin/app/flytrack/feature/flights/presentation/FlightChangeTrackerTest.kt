package app.flytrack.feature.flights.presentation

import app.flytrack.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class FlightChangeTrackerTest {
    private val tracker = FlightChangeTracker()
    private val t0 = TestData.NOW
    private val none = emptySet<FlightField>()

    @Test
    fun `the first sighting is the baseline and marks nothing`() {
        assertEquals(none, tracker.record(TestData.flight(depGate = "42B", depTerminal = "1"), t0))
    }

    @Test
    fun `a value that becomes known is new`() {
        tracker.record(TestData.flight(depGate = null), t0)

        assertEquals(setOf(FlightField.DEP_GATE), tracker.record(TestData.flight(depGate = "42B"), t0 + 1.minutes))
    }

    @Test
    fun `a value that changes is new`() {
        tracker.record(TestData.flight(depGate = "12", baggageBelt = "3"), t0)

        val changed = tracker.record(TestData.flight(depGate = "42B", baggageBelt = "5"), t0 + 1.minutes)

        assertEquals(setOf(FlightField.DEP_GATE, FlightField.BAGGAGE_BELT), changed)
    }

    @Test
    fun `an unchanged flight keeps its marks until the window ends`() {
        val before = TestData.flight(depGate = null)
        val after = TestData.flight(depGate = "42B")
        tracker.record(before, t0)
        tracker.record(after, t0 + 1.minutes)

        assertEquals(setOf(FlightField.DEP_GATE), tracker.record(after, t0 + 30.minutes))
        assertEquals(none, tracker.record(after, t0 + 31.minutes))
        assertEquals(none, tracker.record(after, t0 + 32.minutes))
    }

    @Test
    fun `a second change restarts the window`() {
        tracker.record(TestData.flight(depGate = null), t0)
        tracker.record(TestData.flight(depGate = "12"), t0 + 1.minutes)
        tracker.record(TestData.flight(depGate = "42B"), t0 + 20.minutes)

        assertEquals(setOf(FlightField.DEP_GATE), tracker.record(TestData.flight(depGate = "42B"), t0 + 45.minutes))
    }

    @Test
    fun `a value that is withdrawn is no longer new`() {
        tracker.record(TestData.flight(depGate = null), t0)
        tracker.record(TestData.flight(depGate = "42B"), t0 + 1.minutes)

        assertEquals(none, tracker.record(TestData.flight(depGate = null), t0 + 2.minutes))
    }

    @Test
    fun `blank counts as not announced`() {
        tracker.record(TestData.flight(depGate = null), t0)

        assertEquals(none, tracker.record(TestData.flight(depGate = "  "), t0 + 1.minutes))
    }

    @Test
    fun `flights are tracked separately`() {
        val other = TestData.flight(id = TestData.OTHER_FLIGHT_ID, depGate = "7")
        tracker.record(TestData.flight(depGate = null), t0)
        tracker.record(other, t0)

        assertEquals(setOf(FlightField.DEP_GATE), tracker.record(TestData.flight(depGate = "42B"), t0 + 1.minutes))
        assertEquals(none, tracker.record(other, t0 + 1.minutes))
    }

    @Test
    fun `every screen sees the same marks`() {
        // The list sees the change first; the detail screen opens afterwards with the same data.
        tracker.record(TestData.flight(arrTerminal = "2", baggageBelt = null), t0)
        tracker.record(TestData.flight(arrTerminal = "2", baggageBelt = "5"), t0 + 1.minutes)

        assertEquals(setOf(FlightField.BAGGAGE_BELT), tracker.record(TestData.flight(arrTerminal = "2", baggageBelt = "5"), t0 + 2.minutes))
    }

    @Test
    fun `all six fields are watched`() {
        tracker.record(
            TestData.flight(depTerminal = null, depGate = null, depCheckin = null, arrTerminal = null, arrGate = null, baggageBelt = null),
            t0,
        )

        val all = tracker.record(
            TestData.flight(depTerminal = "1", depGate = "2", depCheckin = "3", arrTerminal = "4", arrGate = "5", baggageBelt = "6"),
            t0 + 1.minutes,
        )

        assertEquals(FlightField.entries.toSet(), all)
    }
}
