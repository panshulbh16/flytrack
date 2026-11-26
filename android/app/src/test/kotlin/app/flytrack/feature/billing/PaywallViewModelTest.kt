package app.flytrack.feature.billing

import app.flytrack.data.remote.FlyTrackJson
import app.flytrack.feature.billing.ui.PaywallContent
import app.flytrack.feature.billing.ui.PaywallMessage
import app.flytrack.feature.billing.ui.PaywallViewModel
import app.flytrack.feature.billing.ui.ProSettingsViewModel
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.FakeBackendApi
import app.flytrack.testing.FakeClock
import app.flytrack.testing.FakeProfileRepository
import app.flytrack.testing.MainDispatcherRule
import app.flytrack.testing.TestData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.days

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PaywallViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val billing = FakeBillingGateway()
    private val api = FakeBackendApi().apply {
        functionHandlers[BillingContract.FUNCTION_SYNC_ENTITLEMENT] = { """{"plan":"pro","pro_until":null,"trial_flight_id":null}""" }
    }
    private val repo = ProRepository(billing, FakeProfileRepository(), FakeAuthRepository(), api, FlyTrackJson, FakeClock(), UnconfinedTestDispatcher())

    private val plans = listOf(
        PlanOption(PlanKind.ANNUAL, BillingContract.PRODUCT_ANNUAL, StorePrice("A", 12_000_000, "INR")),
        PlanOption(PlanKind.MONTHLY, BillingContract.PRODUCT_MONTHLY, StorePrice("M", 2_000_000, "INR")),
        PlanOption(PlanKind.TRIP_PASS, BillingContract.PRODUCT_TRIP_PASS, StorePrice("T", 500_000, "INR")),
    )

    private suspend fun PaywallViewModel.state() = uiState.first { it.content != PaywallContent.Loading }

    @Test fun withoutAKeyThePaywallSaysComingSoon() = runTest {
        billing.isAvailable = false
        val vm = PaywallViewModel(repo)
        assertEquals(PaywallContent.ComingSoon, vm.state().content)
        assertEquals(1, billing.startCalls)
    }

    @Test fun storeWithoutProductsIsComingSoonToo() = runTest {
        billing.offer = OfferResult.NotAvailable
        assertEquals(PaywallContent.ComingSoon, PaywallViewModel(repo).state().content)
    }

    @Test fun plansComeFromTheStoreAndAnnualIsPreselected() = runTest {
        billing.offer = OfferResult.Available(plans)
        val state = PaywallViewModel(repo).state()
        val content = state.content as PaywallContent.Plans
        assertEquals(listOf("A", "M", "T"), content.plans.map { it.price })
        assertEquals(PlanKind.ANNUAL, state.selected)
    }

    @Test fun firstAvailablePlanIsPreselected() = runTest {
        billing.offer = OfferResult.Available(plans.filter { it.kind == PlanKind.TRIP_PASS })
        assertEquals(PlanKind.TRIP_PASS, PaywallViewModel(repo).state().selected)
    }

    @Test fun offlineAndFailureCanBeRetried() = runTest {
        billing.offer = OfferResult.Offline
        val vm = PaywallViewModel(repo)
        assertEquals(PaywallContent.Offline, vm.state().content)
        billing.offer = OfferResult.Failed("boom")
        vm.retry()
        assertEquals(PaywallContent.Failed, vm.state().content)
        billing.offer = OfferResult.Available(plans)
        vm.retry()
        assertTrue(vm.state().content is PaywallContent.Plans)
    }

    @Test fun purchasingTheSelectedPlan() = runTest {
        billing.offer = OfferResult.Available(plans)
        billing.entitlementAfterPurchase = StoreEntitlement(TestData.USER_ID, true, TestData.NOW + 30.days)
        val vm = PaywallViewModel(repo)
        vm.state()
        vm.select(PlanKind.MONTHLY)
        vm.purchase(TestPurchaseHost)
        val state = vm.uiState.first { it.message != null }
        assertEquals(PaywallMessage.PURCHASED, state.message)
        assertEquals(listOf(PlanKind.MONTHLY), billing.purchases)
        assertFalse(state.isPurchasing)
        assertTrue(vm.uiState.first { it.pro.isPro }.pro.isPro)
        vm.messageShown()
        assertNull(vm.uiState.value.message)
    }

    @Test fun purchaseOutcomesBecomeMessages() = runTest {
        billing.offer = OfferResult.Available(plans)
        val vm = PaywallViewModel(repo)
        vm.state()
        val expected = mapOf(
            PurchaseOutcome.Pending to PaywallMessage.PENDING,
            PurchaseOutcome.AlreadyOwned to PaywallMessage.ALREADY_OWNED,
            PurchaseOutcome.Offline to PaywallMessage.OFFLINE,
            PurchaseOutcome.NotAvailable to PaywallMessage.NOT_AVAILABLE,
            PurchaseOutcome.Failed("x") to PaywallMessage.FAILED,
        )
        for ((outcome, message) in expected) {
            billing.purchaseOutcome = outcome
            vm.purchase(TestPurchaseHost)
            assertEquals(message, vm.uiState.first { it.message != null }.message)
            vm.messageShown()
        }
        billing.purchaseOutcome = PurchaseOutcome.Cancelled
        vm.purchase(TestPurchaseHost)
        assertNull(vm.uiState.first { !it.isPurchasing }.message)
    }

    @Test fun restoreMessages() = runTest {
        billing.offer = OfferResult.Available(plans)
        val vm = PaywallViewModel(repo)
        vm.state()
        vm.restore()
        assertEquals(PaywallMessage.RESTORED, vm.uiState.first { it.message != null }.message)
        vm.messageShown()
        billing.restoreOutcome = RestoreOutcome.Restored(hasPro = false)
        vm.restore()
        assertEquals(PaywallMessage.NOTHING_RESTORED, vm.uiState.first { it.message != null }.message)
        vm.messageShown()
        billing.restoreOutcome = RestoreOutcome.Failed("x")
        vm.restore()
        assertEquals(PaywallMessage.RESTORE_FAILED, vm.uiState.first { it.message != null }.message)
    }

    @Test fun settingsRestoreReportsTheOutcome() = runTest {
        val vm = ProSettingsViewModel(repo)
        billing.restoreOutcome = RestoreOutcome.Offline
        vm.restore()
        assertEquals(PaywallMessage.OFFLINE, vm.uiState.value.lastRestore)
        assertFalse(vm.uiState.value.isRestoring)
        assertEquals(1, billing.restoreCalls)
    }
}
