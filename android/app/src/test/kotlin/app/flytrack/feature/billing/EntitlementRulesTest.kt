package app.flytrack.feature.billing

import app.flytrack.core.model.Plan
import app.flytrack.data.remote.dto.ProfileDto
import app.flytrack.data.remote.toDomain
import app.flytrack.data.remote.toEntity
import app.flytrack.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class EntitlementRulesTest {
    private val now = TestData.NOW
    private val user = TestData.USER_ID

    private fun store(
        active: Boolean = false,
        expires: kotlin.time.Instant? = null,
        purchases: List<StorePurchase> = emptyList(),
        appUserId: String = user,
    ) = StoreEntitlement(appUserId, active, expires, purchases)

    @Test fun freeProfileAndNoStoreIsFree() {
        val state = EntitlementRules.resolve(TestData.profile(), null, user, now)
        assertEquals(ProState.Free, state)
    }

    @Test fun nullProfileIsFree() {
        assertFalse(EntitlementRules.resolve(null, null, user, now).isPro)
    }

    @Test fun serverProWithExpiryIsConfirmedPro() {
        val until = now + 30.days
        val state = EntitlementRules.resolve(TestData.profile(plan = Plan.PRO, proUntil = until), null, user, now)
        assertTrue(state.isPro)
        assertTrue(state.confirmedByServer)
        assertEquals(ProSource.SERVER, state.source)
        assertEquals(until, state.proUntil)
    }

    @Test fun expiredServerProIsFree() {
        val state = EntitlementRules.resolve(TestData.profile(plan = Plan.PRO, proUntil = now - 1.minutes), null, user, now)
        assertFalse(state.isPro)
    }

    @Test fun serverProWithoutEndIsLifetime() {
        val state = EntitlementRules.resolve(TestData.profile(plan = Plan.PRO, proUntil = null), store(active = true, expires = now + 5.days), user, now)
        assertTrue(state.isPro)
        assertNull(state.proUntil)
        assertEquals(ProSource.SERVER, state.source)
    }

    @Test fun storeSubscriptionIsOptimisticProBeforeServer() {
        val expires = now + 365.days
        val state = EntitlementRules.resolve(TestData.profile(), store(active = true, expires = expires), user, now)
        assertTrue(state.isPro)
        assertFalse(state.confirmedByServer)
        assertEquals(ProSource.SUBSCRIPTION, state.source)
        assertEquals(expires, state.proUntil)
    }

    @Test fun subscriptionActiveOnlyForAnActiveStoreSubscription() {
        assertTrue(EntitlementRules.resolve(TestData.profile(), store(active = true, expires = now + 3.days), user, now).subscriptionActive)
        assertFalse(EntitlementRules.resolve(TestData.profile(), store(active = true, expires = now - 1.minutes), user, now).subscriptionActive)
        assertFalse(EntitlementRules.resolve(TestData.profile(plan = Plan.PRO, proUntil = now + 3.days), null, user, now).subscriptionActive)
        assertFalse(EntitlementRules.resolve(TestData.profile(), store(active = true, expires = now + 3.days, appUserId = "x"), user, now).subscriptionActive)
    }

    @Test fun inactiveOrExpiredStoreEntitlementIsIgnored() {
        assertFalse(EntitlementRules.resolve(TestData.profile(), store(active = false, expires = now + 3.days), user, now).isPro)
        // CustomerInfo can be stale: an "active" entitlement that already expired is not Pro.
        assertFalse(EntitlementRules.resolve(TestData.profile(), store(active = true, expires = now - 1.minutes), user, now).isPro)
    }

    @Test fun storeOfAnotherUserIsIgnored() {
        val state = EntitlementRules.resolve(TestData.profile(), store(active = true, expires = now + 30.days, appUserId = "someone-else"), user, now)
        assertFalse(state.isPro)
        assertFalse(EntitlementRules.resolve(TestData.profile(), store(active = true, expires = now + 30.days), null, now).isPro)
    }

    @Test fun tripPassGivesSevenDaysFromPurchase() {
        val bought = now - 2.days
        val state = EntitlementRules.resolve(
            TestData.profile(),
            store(purchases = listOf(StorePurchase(BillingContract.PRODUCT_TRIP_PASS, bought))),
            user,
            now,
        )
        assertTrue(state.isPro)
        assertEquals(ProSource.TRIP_PASS, state.source)
        assertEquals(bought + 7.days, state.proUntil)
    }

    @Test fun tripPassExpiresAfterSevenDays() {
        val purchases = listOf(StorePurchase(BillingContract.PRODUCT_TRIP_PASS, now - 7.days))
        assertNull(EntitlementRules.tripPassUntil(purchases, now))
        val almostOver = listOf(StorePurchase(BillingContract.PRODUCT_TRIP_PASS, now - 7.days + 1.hours))
        assertEquals(now + 1.hours, EntitlementRules.tripPassUntil(almostOver, now))
    }

    @Test fun latestTripPassCounts() {
        val purchases = listOf(
            StorePurchase(BillingContract.PRODUCT_TRIP_PASS, now - 30.days),
            StorePurchase(BillingContract.PRODUCT_TRIP_PASS, now - 1.days),
            StorePurchase(BillingContract.PRODUCT_TRIP_PASS, now - 10.days),
        )
        assertEquals(now + 6.days, EntitlementRules.tripPassUntil(purchases, now))
    }

    @Test fun otherOneTimeProductsAreNotTripPasses() {
        val purchases = listOf(StorePurchase("heygate_tip_jar", now - 1.hours))
        assertNull(EntitlementRules.tripPassUntil(purchases, now))
        assertTrue(EntitlementRules.isTripPass("heygate_trip_pass"))
        assertTrue(EntitlementRules.isTripPass("heygate_trip_pass:onetime"))
        assertFalse(EntitlementRules.isTripPass("heygate_trip_pass_2"))
    }

    @Test fun laterGrantWins() {
        // Subscription renews in 3 days, but a trip pass bought today lasts 7.
        val state = EntitlementRules.resolve(
            TestData.profile(plan = Plan.PRO, proUntil = now + 3.days),
            store(active = true, expires = now + 3.days, purchases = listOf(StorePurchase(BillingContract.PRODUCT_TRIP_PASS, now))),
            user,
            now,
        )
        assertEquals(ProSource.TRIP_PASS, state.source)
        assertEquals(now + 7.days, state.proUntil)
        assertTrue(state.confirmedByServer)
    }

    @Test fun equalEndsPreferTheServer() {
        val until = now + 3.days
        val state = EntitlementRules.resolve(TestData.profile(plan = Plan.PRO, proUntil = until), store(active = true, expires = until), user, now)
        assertEquals(ProSource.SERVER, state.source)
    }

    @Test fun trialFlightGivesProOnThatFlightOnly() {
        val state = EntitlementRules.resolve(TestData.profile().copy(trialFlightId = TestData.FLIGHT_ID), null, user, now)
        assertFalse(state.isPro)
        assertEquals(FlightProAccess.TRIAL, state.accessFor(TestData.FLIGHT_ID))
        assertEquals(FlightProAccess.LOCKED, state.accessFor(TestData.OTHER_FLIGHT_ID))
    }

    @Test fun proUsersAreProOnEveryFlightAndNeverSeeTheTrialBadge() {
        val state = EntitlementRules.resolve(
            TestData.profile(plan = Plan.PRO, proUntil = now + 1.days).copy(trialFlightId = TestData.FLIGHT_ID),
            null,
            user,
            now,
        )
        assertEquals(FlightProAccess.PRO, state.accessFor(TestData.FLIGHT_ID))
        assertEquals(FlightProAccess.PRO, state.accessFor(TestData.OTHER_FLIGHT_ID))
    }

    @Test fun userProfileIsProForFlightMatchesServerRule() {
        val trial = TestData.profile().copy(trialFlightId = TestData.FLIGHT_ID)
        assertTrue(trial.isProForFlight(now, TestData.FLIGHT_ID))
        assertFalse(trial.isProForFlight(now, TestData.OTHER_FLIGHT_ID))
        assertTrue(TestData.profile(plan = Plan.PRO).isProForFlight(now, TestData.OTHER_FLIGHT_ID))
    }

    @Test fun trialFlightIdSurvivesTheProfileCache() {
        val dto = ProfileDto(id = user, plan = "free", proUntil = null, trialFlightId = TestData.FLIGHT_ID)
        assertEquals(TestData.FLIGHT_ID, dto.toEntity().toDomain().trialFlightId)
        assertNull(ProfileDto(id = user).toEntity().toDomain().trialFlightId)
    }
}
