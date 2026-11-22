package app.flytrack.testing

import app.cash.turbine.test
import app.flytrack.core.model.AlertType
import app.flytrack.core.model.TrackExtras
import app.flytrack.core.result.ActionResult
import app.flytrack.core.result.SearchResult
import app.flytrack.core.result.TrackResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fakes are a contract for the feature tests, so their default behaviour is pinned down here. */
class FakeRepositoriesTest {
    private val repo = FakeFlightRepository()
    private val flight = TestData.flight()

    @Test
    fun `search then track makes the flight appear in the list`() = runTest {
        repo.searchResult = SearchResult.Found(listOf(flight))

        repo.observeTrackedFlights().test {
            assertTrue(awaitItem().isEmpty())
            assertEquals(SearchResult.Found(listOf(flight)), repo.search("6E 2341", LocalDate.parse("2026-11-25")))
            assertEquals(flight, repo.observeFlight(flight.id).first())

            val result = repo.track(flight.id, TrackExtras(pnr = "ABC123"))
            assertEquals("ABC123", (result as TrackResult.Tracked).tracking.pnr)
            assertEquals(listOf(flight.id), awaitItem().map { it.flight.id })
        }
        assertTrue(repo.track(flight.id) is TrackResult.AlreadyTracked)
        assertEquals(TrackResult.FlightNotFound, repo.track("unknown"))
    }

    @Test
    fun `archive, prefs and untrack change the observed state`() = runTest {
        repo.setTracked(TestData.tracked(flight))

        repo.updateNotifyPrefs(flight.id, mapOf(AlertType.DEPARTED to false))
        assertFalse(repo.observeTrackedFlight(flight.id).first()!!.tracking.notifies(AlertType.DEPARTED))

        repo.archive(flight.id)
        assertTrue(repo.observeTrackedFlights().first().isEmpty())
        assertTrue(repo.observeTrackedFlight(flight.id).first()!!.tracking.isArchived)

        assertEquals(ActionResult.Success, repo.untrack(flight.id))
        assertNull(repo.observeTrackedFlight(flight.id).first())
        assertEquals(ActionResult.NotFound, repo.untrack(flight.id))
    }

    @Test
    fun `forced results win over the default behaviour`() = runTest {
        repo.setTracked(TestData.tracked(flight))
        repo.trackResult = TrackResult.TrackingLimitReached()
        repo.actionResult = ActionResult.Offline
        repo.refreshResult = ActionResult.NotConfigured

        assertEquals(TrackResult.TrackingLimitReached(), repo.track(flight.id))
        assertEquals(ActionResult.Offline, repo.archive(flight.id))
        assertEquals(ActionResult.NotConfigured, repo.refresh())
        assertEquals(1, repo.observeTrackedFlights().first().size)
    }

    @Test
    fun `fake auth signs in on demand`() = runTest {
        val auth = FakeAuthRepository.signedOut()
        assertNull(auth.currentUserId)
        auth.ensureSignedIn()
        assertEquals(TestData.USER_ID, auth.currentUserId)
        auth.linkGoogle("token")
        assertEquals(listOf("token"), auth.linkedIdTokens)
    }
}
