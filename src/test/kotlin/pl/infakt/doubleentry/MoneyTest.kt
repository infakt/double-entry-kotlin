package pl.infakt.doubleentry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.Currency
import org.junit.jupiter.api.Test

class MoneyTest {
    private val usd = Currency.getInstance("USD")
    private val eur = Currency.getInstance("EUR")

    @Test
    fun `an amount is minor units of a currency, given by ISO code or as a Currency`() {
        val money = Money.of(100_01, "USD")

        money.minorUnits shouldBe 100_01L
        money.currency shouldBe usd
        money shouldBe Money(100_01, usd)
    }

    @Test
    fun `zero is no money in the given currency`() {
        Money.zero(eur) shouldBe Money(0, eur)
        Money.zero(eur).isZero shouldBe true
    }

    @Test
    fun `the sign of the minor units tells negative, positive and zero apart`() {
        val signs = listOf(-1L, 0L, 1L).map { Money(it, usd) }.map { Triple(it.isNegative, it.isZero, it.isPositive) }

        signs shouldBe listOf(Triple(true, false, false), Triple(false, true, false), Triple(false, false, true))
    }

    @Test
    fun `plus, minus and unary minus work on the minor units of one currency`() {
        Money(100_01, usd) + Money(-1, usd) shouldBe Money(100_00, usd)
        Money(100_01, usd) - Money(1_00, usd) shouldBe Money(99_01, usd)
        -Money(100_01, usd) shouldBe Money(-100_01, usd)
        -Money.zero(usd) shouldBe Money.zero(usd)
    }

    @Test
    fun `amounts of one currency compare and equal by their minor units`() {
        Money(1, usd).compareTo(Money(2, usd)) shouldBe -1
        Money(2, usd).compareTo(Money(1, usd)) shouldBe 1
        Money(2, usd).compareTo(Money(2, usd)) shouldBe 0
        listOf(Money(3, usd), Money(-1, usd), Money(2, usd)).sorted() shouldBe
            listOf(Money(-1, usd), Money(2, usd), Money(3, usd))
        Money(2, usd) shouldBe Money.of(2, "USD")
        Money(2, usd) shouldNotBe Money(3, usd)
        Money(2, usd) shouldNotBe Money(2, eur)
    }

    @Test
    fun `plus and minus across currencies throw CurrencyMismatchException`() {
        val plus = shouldThrow<CurrencyMismatchException> { Money(1, usd) + Money(1, eur) }
        val minus = shouldThrow<CurrencyMismatchException> { Money(1, eur) - Money(1, usd) }

        (plus.left to plus.right) shouldBe (usd to eur)
        plus.message shouldBe "Money in USD and EUR cannot be combined"
        (minus.left to minus.right) shouldBe (eur to usd)
    }

    @Test
    fun `comparing across currencies throws CurrencyMismatchException`() {
        shouldThrow<CurrencyMismatchException> { Money(1, usd).compareTo(Money(1, eur)) }
    }

    @Test
    fun `CurrencyMismatchException is no DoubleEntryException`() {
        DoubleEntryException::class.java.isAssignableFrom(CurrencyMismatchException::class.java) shouldBe false
        RuntimeException::class.java.isAssignableFrom(CurrencyMismatchException::class.java) shouldBe true
    }

    @Test
    fun `arithmetic beyond the range of a Long throws ArithmeticException`() {
        shouldThrow<ArithmeticException> { Money(Long.MAX_VALUE, usd) + Money(1, usd) }
        shouldThrow<ArithmeticException> { Money(Long.MIN_VALUE, usd) - Money(1, usd) }
        shouldThrow<ArithmeticException> { -Money(Long.MIN_VALUE, usd) }
    }

    @Test
    fun `toString writes major units with the currency's decimal places and no code, as Money#to_s does`() {
        Money.of(100_01, "USD").toString() shouldBe "100.01"
        Money.of(-1, "USD").toString() shouldBe "-0.01"
        Money.of(100_00, "USD").toString() shouldBe "100.00"
        Money.of(100, "JPY").toString() shouldBe "100"
    }

    @Test
    fun `toString writes a currency without decimal places in java util Currency as whole units`() {
        Money.of(100, "XAU").toString() shouldBe "100"
    }

    @Test
    fun `BHD with three decimal places stands in for BTC, which java util Currency does not know (X-02)`() {
        Money.of(-10, "BHD").toString() shouldBe "-0.010"
        Money.of(200_00, "BHD").toString() shouldBe "20.000"
        shouldThrow<IllegalArgumentException> { Money.of(1, "BTC") }
    }
}
