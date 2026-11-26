package app.flytrack.feature.billing

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Figures derived from the store prices. Nothing here knows a price: everything is computed from
 * what the Play Console returned through RevenueCat.
 */
object PlanPricing {
    private const val MICROS = 1_000_000L

    /**
     * The annual price spread over twelve months, whole currency units ("₹417"); null when the
     * price is unknown.
     */
    fun monthlyEquivalent(annual: StorePrice, locale: Locale = Locale.getDefault()): String? {
        if (annual.amountMicros <= 0) return null
        val perMonth = BigDecimal.valueOf(annual.amountMicros)
            .divide(BigDecimal.valueOf(12 * MICROS), 0, RoundingMode.HALF_UP)
        return format(perMonth, annual.currencyCode, locale)
    }

    /**
     * How much the annual plan saves against twelve monthly payments, rounded down to a whole
     * percent. null when the currencies differ, a price is unknown, or the saving is under 1%.
     */
    fun annualSavingPercent(annual: StorePrice, monthly: StorePrice): Int? {
        if (annual.currencyCode != monthly.currencyCode) return null
        if (annual.amountMicros <= 0 || monthly.amountMicros <= 0) return null
        val yearOfMonthly = monthly.amountMicros * 12
        if (annual.amountMicros >= yearOfMonthly) return null
        val percent = ((yearOfMonthly - annual.amountMicros) * 100 / yearOfMonthly).toInt()
        return percent.takeIf { it >= 1 }
    }

    private fun format(amount: BigDecimal, currencyCode: String, locale: Locale): String? {
        val currency = runCatching { Currency.getInstance(currencyCode) }.getOrNull() ?: return null
        val format = NumberFormat.getCurrencyInstance(locale).apply {
            this.currency = currency
            minimumFractionDigits = 0
            maximumFractionDigits = 0
        }
        return format.format(amount)
    }
}
