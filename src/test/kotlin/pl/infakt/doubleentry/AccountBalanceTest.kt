package pl.infakt.doubleentry

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import java.util.Currency
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.support.SpecAccounts
import pl.infakt.doubleentry.support.TestDatabase
import pl.infakt.doubleentry.support.TestUser

/**
 * account_balance_spec.rb:8-29: `AccountBalance.scopes_with_minimum_balance_for_account`, through
 * `DoubleEntry.accountBalances`, on the spec accounts (`support/SpecAccounts.kt`).
 */
class AccountBalanceTest {
    private val database: Database = TestDatabase.ledger(jsonMetadata = false)

    private val usd: Currency = Currency.getInstance("USD")

    private val doubleEntry = SpecAccounts.doubleEntry(database)

    // account_balance_spec.rb:12, `create(:user, checking_balance: Money.new(100_00))`.
    private val user = TestUser(1)

    // account_balance_spec.rb:11-12: a 'checking' account with balance $100, `checking_balance` of factories.rb:24-31.
    @BeforeEach
    fun checkingBalance() {
        doubleEntry.transfer(
            dollars(100_00),
            from = doubleEntry.account("test", scope = user),
            to = doubleEntry.account("checking", scope = user),
            code = "pay",
        )
    }

    @Test
    fun `minimum balance for checking, balance $100, when searching for $99, should include the user id`() {
        scopes(minimum = dollars(99_00)) shouldContain user.id.toString()
    }

    @Test
    fun `minimum balance for checking, balance $100, when searching for $100, should include the user id`() {
        scopes(minimum = dollars(100_00)) shouldContain user.id.toString()
    }

    @Test
    fun `minimum balance for checking, balance $100, when searching for $101, should not include the user id`() {
        scopes(minimum = dollars(101_00)) shouldNotContain user.id.toString()
    }

    @Test
    fun `the scopes are those of the identifier's records at or above the minimum, in the order of the records`() {
        val other = TestUser(2)
        val third = TestUser(3)
        doubleEntry.transfer(
            dollars(250_00),
            from = doubleEntry.account("test", scope = third),
            to = doubleEntry.account("checking", scope = third),
            code = "pay",
        )
        doubleEntry.transfer(
            dollars(50_00),
            from = doubleEntry.account("test", scope = other),
            to = doubleEntry.account("checking", scope = other),
            code = "pay",
        )

        scopes(minimum = dollars(100_00)) shouldBe listOf("1", "3")
        scopes(minimum = dollars(0)) shouldBe listOf("1", "3", "2")
        val tests = doubleEntry.accountBalances.scopesWithMinimumBalanceForAccount(dollars(-100_00), "test")
        tests shouldBe listOf("1", "2")
        doubleEntry.accountBalances.scopesWithMinimumBalanceForAccount(dollars(0), "savings") shouldBe emptyList()
    }

    @Test
    fun `an unscoped account's scope is null, and only the minor units of the minimum are compared`() {
        val bhd = Currency.getInstance("BHD")
        val unscoped =
            DoubleEntry(database) {
                accounts {
                    define("bank")
                    define("vault")
                }
                transfers { define("bank", "vault", "deposit") }
            }
        unscoped.transfer(
            dollars(10_00),
            from = unscoped.account("bank"),
            to = unscoped.account("vault"),
            code = "deposit",
        )

        unscoped.accountBalances.scopesWithMinimumBalanceForAccount(Money(10_00, bhd), "vault") shouldBe listOf(null)
    }

    private fun scopes(minimum: Money): List<String?> =
        doubleEntry.accountBalances.scopesWithMinimumBalanceForAccount(minimum, "checking")

    private fun dollars(cents: Long): Money = Money(cents, usd)
}
