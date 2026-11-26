package app.flytrack.core.time

import app.flytrack.core.model.RouteSearchQuery
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/** P3 B5 / B6 date windows, mirrored from route-search.ts and past-search.ts. */
class SearchWindowsTest {
    private val now = Instant.parse("2026-11-22T10:00:00Z")

    @Test fun pastWindowIsAYearBackToTomorrowUtc() {
        assertEquals(LocalDate.parse("2025-11-22")..LocalDate.parse("2026-11-23"), PastSearchWindow.dates(now))
        assertTrue(PastSearchWindow.contains(LocalDate.parse("2025-11-22"), now))
        assertFalse(PastSearchWindow.contains(LocalDate.parse("2025-11-21"), now))
        assertFalse(PastSearchWindow.contains(LocalDate.parse("2026-11-24"), now))
    }

    @Test fun routeWindowIsTwoDaysBackToThirtyAhead() {
        // 2026-11-20 00:00Z is more than 48 h before now; the 21st is not.
        assertEquals(LocalDate.parse("2026-11-21")..LocalDate.parse("2026-12-22"), RouteSearchWindow.dates(now))
        assertFalse(RouteSearchWindow.contains(LocalDate.parse("2026-11-20"), now))
        assertTrue(RouteSearchWindow.contains(LocalDate.parse("2026-12-22"), now))
        assertFalse(RouteSearchWindow.contains(LocalDate.parse("2026-12-23"), now))
    }

    @Test fun routeQueriesNormaliseAndValidateLikeTheServer() {
        val d = LocalDate.parse("2026-11-28")
        assertEquals(RouteSearchQuery("DEL", null, d, "6E"), RouteSearchQuery(" del ", " ", d, "6e").normalized())
        assertTrue(RouteSearchQuery("DEL", "BOM", d, "6E").isValid)
        assertFalse(RouteSearchQuery("DEL", "DEL", d).isValid)
        assertFalse(RouteSearchQuery("DELH", null, d).isValid)
        assertFalse(RouteSearchQuery("DEL", null, d, "6EE").isValid)
        assertEquals("6E", RouteSearchQuery.sanitizeAirlineInput(" 6e-x"))
    }
}
