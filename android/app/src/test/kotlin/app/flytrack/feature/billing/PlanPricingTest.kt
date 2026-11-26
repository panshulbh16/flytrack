package app.flytrack.feature.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** Store answers as Google Play would send them for the founder's console prices. */
class PlanPricingTest {
    private val india = Locale.forLanguageTag("en-IN")
    private val annual = StorePrice("₹4,999.00", 4_999_000_000, "INR")
    private val monthly = StorePrice("₹499.00", 499_000_000, "INR")

    @Test fun annualPerMonthIsRoundedToWholeRupees() {
        assertEquals("₹417", PlanPricing.monthlyEquivalent(annual, india))
    }

    @Test fun annualSavingIsRoundedDown() {
        // 12 x 499 = 5,988; 4,999 saves 989 = 16.5 %
        assertEquals(16, PlanPricing.annualSavingPercent(annual, monthly))
    }

    @Test fun noSavingWhenAnnualIsNotCheaper() {
        assertNull(PlanPricing.annualSavingPercent(StorePrice("₹5,988.00", 5_988_000_000, "INR"), monthly))
        assertNull(PlanPricing.annualSavingPercent(StorePrice("₹6,000.00", 6_000_000_000, "INR"), monthly))
    }

    @Test fun noSavingAcrossCurrenciesOrUnknownPrices() {
        assertNull(PlanPricing.annualSavingPercent(StorePrice("$59.99", 59_990_000, "USD"), monthly))
        assertNull(PlanPricing.annualSavingPercent(StorePrice("", 0, "INR"), monthly))
        assertNull(PlanPricing.monthlyEquivalent(StorePrice("", 0, "INR"), india))
        assertNull(PlanPricing.monthlyEquivalent(StorePrice("?", 1_000_000, "XX1"), india))
    }

    @Test fun rowsComeSortedWithDerivedFiguresOnTheAnnualPlanOnly() {
        val rows = app.flytrack.feature.billing.ui.PaywallViewModel.planRows(
            listOf(
                PlanOption(PlanKind.TRIP_PASS, BillingContract.PRODUCT_TRIP_PASS, StorePrice("₹99.00", 99_000_000, "INR")),
                PlanOption(PlanKind.MONTHLY, BillingContract.PRODUCT_MONTHLY, monthly),
                PlanOption(PlanKind.ANNUAL, BillingContract.PRODUCT_ANNUAL, annual),
            ),
            india,
        )
        assertEquals(listOf(PlanKind.ANNUAL, PlanKind.MONTHLY, PlanKind.TRIP_PASS), rows.map { it.kind })
        assertEquals(app.flytrack.feature.billing.ui.PlanRow(PlanKind.ANNUAL, "₹4,999.00", "₹417", 16), rows[0])
        assertEquals(app.flytrack.feature.billing.ui.PlanRow(PlanKind.MONTHLY, "₹499.00"), rows[1])
        assertEquals(app.flytrack.feature.billing.ui.PlanRow(PlanKind.TRIP_PASS, "₹99.00"), rows[2])
    }

    @Test fun annualWithoutMonthlyHasNoSaving() {
        val rows = app.flytrack.feature.billing.ui.PaywallViewModel.planRows(
            listOf(PlanOption(PlanKind.ANNUAL, BillingContract.PRODUCT_ANNUAL, annual)),
            india,
        )
        assertNull(rows.single().savePercent)
    }
}
