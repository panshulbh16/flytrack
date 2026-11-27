package app.flytrack.feature.billing

import app.flytrack.core.model.Plan
import app.flytrack.data.remote.FlyTrackJson
import app.flytrack.feature.social.FamilyGrant
import app.flytrack.feature.social.InMemorySocialStore
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.FakeBackendApi
import app.flytrack.testing.FakeClock
import app.flytrack.testing.FakeProfileRepository
import app.flytrack.testing.TestData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days

/** P3 B13: "Pro via {owner}'s family" in the app's Pro state. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FamilyEntitlementTest {
    private val now = TestData.NOW
    private val user = TestData.USER_ID

    @Test fun aFamilyGrantMakesAFreeMemberPro() {
        val state = EntitlementRules.resolve(TestData.profile(), null, user, now, FamilyGrant(user, "Asha"))
        assertTrue(state.isPro)
        assertEquals(ProSource.FAMILY, state.source)
        assertTrue(state.isViaFamily)
        assertEquals("Asha", state.familyOwnerName)
        assertNull(state.proUntil)
        assertTrue(state.confirmedByServer)
    }

    @Test fun ownProAlwaysWins() {
        val until = now + 30.days
        val state = EntitlementRules.resolve(TestData.profile(plan = Plan.PRO, proUntil = until), null, user, now, FamilyGrant(user, "Asha"))
        assertEquals(ProSource.SERVER, state.source)
        assertFalse(state.isViaFamily)
        assertEquals(until, state.proUntil)
    }

    @Test fun anotherUsersGrantIsIgnored() {
        assertFalse(EntitlementRules.resolve(TestData.profile(), null, user, now, FamilyGrant("other", "Asha")).isPro)
        assertFalse(EntitlementRules.resolve(TestData.profile(), null, null, now, FamilyGrant(user, "Asha")).isPro)
    }

    @Test fun theRepositoryFollowsTheCachedGrant() = runTest {
        val social = InMemorySocialStore()
        val repo = ProRepository(FakeBillingGateway(), FakeProfileRepository(), FakeAuthRepository(), FakeBackendApi(), FlyTrackJson, FakeClock(), UnconfinedTestDispatcher(), social)
        assertFalse(repo.observe().first().isPro)
        social.family.value = FamilyGrant(user, null)
        val state = repo.observe().first()
        assertTrue(state.isViaFamily)
        assertNull(state.familyOwnerName)
    }

    @Test fun syncEntitlementCachesViaFamily() = runTest {
        val social = InMemorySocialStore()
        val api = FakeBackendApi()
        val repo = ProRepository(FakeBillingGateway(), FakeProfileRepository(), FakeAuthRepository(), api, FlyTrackJson, FakeClock(), UnconfinedTestDispatcher(), social)
        api.functionHandlers[BillingContract.FUNCTION_SYNC_ENTITLEMENT] = {
            """{"plan":"pro","pro_until":null,"trial_flight_id":null,"via_family":{"owner_name":"Asha"}}"""
        }
        repo.syncEntitlement()
        assertEquals(FamilyGrant(user, "Asha"), social.family.value)

        api.functionHandlers[BillingContract.FUNCTION_SYNC_ENTITLEMENT] = { """{"plan":"free","pro_until":null,"trial_flight_id":null,"via_family":null}""" }
        repo.syncEntitlement()
        assertNull(social.family.value)
    }
}
