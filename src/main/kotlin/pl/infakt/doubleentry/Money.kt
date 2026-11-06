package pl.infakt.doubleentry

import java.math.BigDecimal
import java.util.Currency

/**
 * An amount of money: [minorUnits] (cents for USD, fils for BHD) of a [currency], the port's stand-in for the money
 * gem's `Money` (decision 0001, §3). Arithmetic and comparison between two currencies throw
 * [CurrencyMismatchException]; arithmetic that leaves the range of a [Long] throws [ArithmeticException].
 */
data class Money(
    val minorUnits: Long,
    val currency: Currency,
) : Comparable<Money> {
    /** Whether the amount is below zero. */
    val isNegative: Boolean
        get() = minorUnits < 0

    /** Whether the amount is above zero. */
    val isPositive: Boolean
        get() = minorUnits > 0

    /** Whether the amount is zero. */
    val isZero: Boolean
        get() = minorUnits == 0L

    /** @throws CurrencyMismatchException when [other] is in another currency. */
    operator fun plus(other: Money): Money = Money(Math.addExact(minorUnits, sameCurrency(other).minorUnits), currency)

    /** @throws CurrencyMismatchException when [other] is in another currency. */
    operator fun minus(other: Money): Money =
        Money(Math.subtractExact(minorUnits, sameCurrency(other).minorUnits), currency)

    operator fun unaryMinus(): Money = Money(Math.negateExact(minorUnits), currency)

    /** @throws CurrencyMismatchException when [other] is in another currency. */
    override fun compareTo(other: Money): Int = minorUnits.compareTo(sameCurrency(other).minorUnits)

    /**
     * The amount in major units with the currency's decimal places and no currency code, as `Money#to_s` writes it:
     * `100.01` for 10001 USD, `-0.010` for -10 BHD, `100` for 100 JPY.
     */
    override fun toString(): String =
        BigDecimal.valueOf(minorUnits, currency.defaultFractionDigits.coerceAtLeast(0)).toPlainString()

    private fun sameCurrency(other: Money): Money {
        if (other.currency != currency) throw CurrencyMismatchException(currency, other.currency)
        return other
    }

    companion object {
        /** No money in [currency]. */
        fun zero(currency: Currency): Money = Money(0, currency)

        /**
         * [minorUnits] of the currency of ISO 4217 code [currencyCode].
         *
         * @throws IllegalArgumentException when `java.util.Currency` does not know [currencyCode], such as BTC
         *   (docs/deviations.md, X-02).
         */
        fun of(
            minorUnits: Long,
            currencyCode: String,
        ): Money = Money(minorUnits, Currency.getInstance(currencyCode))
    }
}

/** Arithmetic or a comparison between amounts of two currencies. Not a [DoubleEntryException]: the gem has none. */
class CurrencyMismatchException(
    val left: Currency,
    val right: Currency,
) : RuntimeException("Money in ${left.currencyCode} and ${right.currencyCode} cannot be combined")
