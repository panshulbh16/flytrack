package app.flytrack.feature.live

import app.flytrack.feature.widget.NextFlightWidgetModel
import app.flytrack.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class LivePlannerTest {

    private val planner = LivePlanner(FakeLiveStrings)
    private val dep: Instant = TestData.flight().schedDep!!
    private val tracked = listOf(TestData.tracked(TestData.flight(lastPolledAt = dep - 1.hours)))

    @Test fun proFlightInsideTheWindowGetsTheNotificationAndTheTick() {
        val plan = planner.plan(tracked, dep - 1.hours, isEligible = { true }, dismissed = emptySet())
        assertNotNull(plan.notification)
        assertTrue(plan.needsTick)
        assertNull(plan.nextWindowStart)
        assertTrue(plan.widget is NextFlightWidgetModel.Upcoming)
    }

    @Test fun freeFlightGetsTheWidgetButNoNotification() {
        val plan = planner.plan(tracked, dep - 1.hours, isEligible = { false }, dismissed = emptySet())
        assertNull(plan.notification)
        assertTrue(plan.needsTick)
        assertTrue(plan.widget is NextFlightWidgetModel.Upcoming)
    }

    @Test fun dismissedFlightHasNoNotification() {
        val plan = planner.plan(tracked, dep - 1.hours, isEligible = { true }, dismissed = setOf(TestData.FLIGHT_ID))
        assertNull(plan.notification)
    }

    @Test fun farFlightNeedsOnlyAWakeUpAtWindowStart() {
        val now = dep - 40.hours
        val plan = planner.plan(tracked, now, isEligible = { true }, dismissed = emptySet())
        assertNull(plan.notification)
        assertFalse(plan.needsTick)
        assertEquals(dep - 3.hours, plan.nextWindowStart)
    }

    @Test fun nothingTrackedNeedsNothing() {
        val plan = planner.plan(emptyList(), dep, isEligible = { true }, dismissed = emptySet())
        assertNull(plan.notification)
        assertFalse(plan.needsTick)
        assertNull(plan.nextWindowStart)
        assertEquals(NextFlightWidgetModel.Empty, plan.widget)
    }
}
