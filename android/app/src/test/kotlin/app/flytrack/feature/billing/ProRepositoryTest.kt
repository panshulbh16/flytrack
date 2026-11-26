package app.flytrack.feature.billing

import app.cash.turbine.test
import app.flytrack.core.model.Plan
import app.flytrack.core.result.ActionResult
import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendException
import app.flytrack.data.remote.FlyTrackJson
import app.flytrack.data.repo.SessionState
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.FakeBackendApi
import app.flytrack.testing.FakeClock
import app.flytrack.testing.FakeProfileRepository
import app.flytrack.testing.TestData
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProRepositoryTest {
    private val billing = FakeBillingGateway()
    private val profiles = FakeProfileRepository()
    private val auth = FakeAuthRepository()
    private val api = FakeBackendApi()
    private val clock = FakeClock()
    private val syncResponse = """{"plan":"pro","pro_until":"2027-11-22T10:00:00Z","trial_flight_id":null}"""

    private fun repo() = ProRepository(billing, profiles, auth, api, FlyTrackJson, clock, UnconfinedTestDispatcher())

    private val syncCalls get() = api.calls.count { it.startsWith("invokeFunction(${BillingContract.FUNCTION_SYNC_ENTITLEMENT}") }

    @Test fun purchaseCallsSyncEntitlementThenRefreshesTheProfile() = runTest {
        api.functionHandlers[BillingContract.FUNCTION_SYNC_ENTITLEMENT] = { body ->
            assertEquals("{}", body)
            syncResponse
        }
        val outcome = repo().purchase(TestPurchaseHost, PlanKind.ANNUAL)
        assertEquals(PurchaseOutcome.Purchased, outcome)
        assertEquals(listOf(PlanKind.ANNUAL), billing.purchases)
        assertEquals(1, syncCalls)
        assertEquals(1, profiles.refreshCalls)
    }

    @Test fun cancelledOrPendingPurchaseDoesNotSync() = runTest {
        for (outcome in listOf(PurchaseOutcome.Cancelled, PurchaseOutcome.Pending, PurchaseOutcome.Failed("x"))) {
            billing.purchaseOutcome = outcome
            assertEquals(outcome, repo().purchase(TestPurchaseHost, PlanKind.TRIP_PASS))
        }
        assertEquals(0, syncCalls)
        assertEquals(0, profiles.refreshCalls)
    }

    @Test fun purchaseStaysSuccessfulWhenSyncFails() = runTest {
        api.functionHandlers[BillingContract.FUNCTION_SYNC_ENTITLEMENT] = { throw BackendException.Http(500, "boom", "boom") }
        assertEquals(PurchaseOutcome.Purchased, repo().purchase(TestPurchaseHost, PlanKind.MONTHLY))
        // The profile is still refreshed: the webhook may already have updated it.
        assertEquals(1, profiles.refreshCalls)
    }

    @Test fun restoreSyncs() = runTest {
        api.functionHandlers[BillingContract.FUNCTION_SYNC_ENTITLEMENT] = { syncResponse }
        assertEquals(RestoreOutcome.Restored(hasPro = true), repo().restore())
        assertEquals(1, syncCalls)
    }

    @Test fun failedRestoreDoesNotSync() = runTest {
        billing.restoreOutcome = RestoreOutcome.Offline
        assertEquals(RestoreOutcome.Offline, repo().restore())
        assertEquals(0, syncCalls)
    }

    @Test fun syncEntitlementMapsErrors() = runTest {
        val repo = repo()
        // Function not deployed yet: 404 from the fake.
        assertTrue(repo.syncEntitlement() is ActionResult.Failed)

        api.functionHandlers[BillingContract.FUNCTION_SYNC_ENTITLEMENT] = { "not json" }
        assertTrue(repo.syncEntitlement() is ActionResult.Failed)

        api.functionHandlers[BillingContract.FUNCTION_SYNC_ENTITLEMENT] = { syncResponse }
        assertEquals(ActionResult.Success, repo.syncEntitlement())

        profiles.refreshResult = ActionResult.Offline
        assertEquals(ActionResult.Offline, repo.syncEntitlement())
    }

    @Test fun syncEntitlementNeedsASession() = runTest {
        auth.nextResult = AuthResult.Offline
        assertEquals(ActionResult.Offline, repo().syncEntitlement())
        assertEquals(0, syncCalls)
    }

    @Test fun noSecondPurchaseOnTopOfAnActiveSubscription() = runTest {
        billing.store.value = StoreEntitlement(TestData.USER_ID, true, TestData.NOW + 30.days)
        assertEquals(PurchaseOutcome.AlreadyOwned, repo().purchase(TestPurchaseHost, PlanKind.ANNUAL))
        assertEquals(PurchaseOutcome.AlreadyOwned, repo().purchase(TestPurchaseHost, PlanKind.TRIP_PASS))
        assertTrue(billing.purchases.isEmpty())
        assertEquals(0, syncCalls)
    }

    @Test fun observeShowsProOptimisticallyAfterPurchaseThenConfirmed() = runTest {
        api.functionHandlers[BillingContract.FUNCTION_SYNC_ENTITLEMENT] = { syncResponse }
        billing.entitlementAfterPurchase = StoreEntitlement(TestData.USER_ID, true, TestData.NOW + 365.days)
        val repo = repo()
        repo.observe().test {
            assertFalse(awaitItem().isPro)

            repo.purchase(TestPurchaseHost, PlanKind.ANNUAL)
            val optimistic = awaitItem()
            assertTrue(optimistic.isPro)
            assertFalse(optimistic.confirmedByServer)

            profiles.profile.value = TestData.profile(plan = Plan.PRO, proUntil = TestData.NOW + 365.days)
            val confirmed = awaitItem()
            assertTrue(confirmed.isPro)
            assertTrue(confirmed.confirmedByServer)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun observeDropsProWhenItExpires() = runTest {
        profiles.profile.value = TestData.profile(plan = Plan.PRO, proUntil = TestData.NOW + 30.minutes)
        repo().observe(tick = 1.minutes).test {
            assertTrue(awaitItem().isPro)
            clock.advance(31.minutes)
            // The next tick re-evaluates with the new time.
            assertFalse(awaitItem().isPro)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun observeIgnoresTheStoreOfThePreviousUser() = runTest {
        billing.store.value = StoreEntitlement(TestData.USER_ID, true, TestData.NOW + 30.days)
        repo().observe().test {
            assertTrue(awaitItem().isPro)
            auth.setSession(SessionState.SignedIn("22222222-2222-4222-8222-222222222222", isAnonymous = true, email = null))
            assertFalse(awaitItem().isPro)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun startStartsTheGateway() {
        repo().start()
        assertEquals(1, billing.startCalls)
    }
}
