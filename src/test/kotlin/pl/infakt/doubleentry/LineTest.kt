package pl.infakt.doubleentry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.sql.SQLException
import java.util.Currency
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.schema.LinesTable
import pl.infakt.doubleentry.support.SpecAccounts
import pl.infakt.doubleentry.support.TestDatabase

/**
 * line_spec.rb: a line saved with `Lines.insert` and read back with `Lines.find`, on the spec accounts, BHD in place of
 * BTC (X-02).
 */
class LineTest {
    private val database: Database = TestDatabase.ledger(jsonMetadata = false)

    private val usd: Currency = Currency.getInstance("USD")

    private val bhd: Currency = Currency.getInstance("BHD")

    private val doubleEntry =
        SpecAccounts.doubleEntry(database) {
            // line_spec.rb:68 and 78.
            accounts {
                define("a_positive_only_acc", positiveOnly = true)
                define("a_negative_only_acc", negativeOnly = true)
            }
        }

    private val lines = doubleEntry.lines

    // line_spec.rb:17-19.
    private val account = doubleEntry.account("test", scopeIdentity = "17")
    private val partnerAccount = doubleEntry.account("test", scopeIdentity = "72")

    @Test
    fun `persistance attributes given code the_code, its code should eq the_code`() {
        persisted(code = "the_code").code shouldBe "the_code"
    }

    @Test
    fun `persistance attributes given code nil, saving raises a NOT NULL violation`() {
        val error = shouldThrow<SQLException> { lines.insert(account, partnerAccount, null, tenDollars(), zero()) }

        error.sqlState shouldBe "23502"
        lineCount() shouldBe 0
    }

    @Test
    fun `persistance attributes given account test 54, its account account identifier should eq test`() {
        val line = persisted(account = doubleEntry.account("test", scopeIdentity = "54"))

        line.account.account.identifier shouldBe "test"
    }

    @Test
    fun `persistance attributes given account test 54, its account scope_identity should eq 54`() {
        val line = persisted(account = doubleEntry.account("test", scopeIdentity = "54"))

        line.account.scopeIdentity shouldBe "54"
    }

    @Test
    fun `persistance attributes given partner_account test 91, its partner_account identifier should eq test`() {
        val line = persisted(partnerAccount = doubleEntry.account("test", scopeIdentity = "91"))

        line.partnerAccount.account.identifier shouldBe "test"
    }

    @Test
    fun `persistance attributes given partner_account test 91, its partner_account scope_identity should eq 91`() {
        val line = persisted(partnerAccount = doubleEntry.account("test", scopeIdentity = "91"))

        line.partnerAccount.scopeIdentity shouldBe "91"
    }

    @Test
    fun `persistance attributes currency, its currency should eq BHD`() {
        val line =
            persisted(
                account = doubleEntry.account("bhd_test", scopeIdentity = "17"),
                partnerAccount = doubleEntry.account("bhd_test", scopeIdentity = "72"),
                amount = Money(10_00, bhd),
                balance = Money.zero(bhd),
            )

        line.account.currency shouldBe bhd
        line.amount.currency shouldBe bhd
        line.balance.currency shouldBe bhd
    }

    @Test
    fun `persistance attributes given detail nil, saving does not raise`() {
        persisted().detail shouldBe null
    }

    @Test
    fun `persistance when balance is sent negative raises AccountWouldBeSentNegative error`() {
        val positiveOnly = doubleEntry.account("a_positive_only_acc")

        val error =
            shouldThrow<AccountWouldBeSentNegativeException> {
                lines.insert(positiveOnly, positiveOnly, "test_code", Money(-1, usd), Money(-1, usd))
            }

        error.message shouldBe "#{Account account: a_positive_only_acc scope:  currency: USD}"
        lineCount() shouldBe 0
    }

    @Test
    fun `persistance when balance is sent positive raises AccountWouldBeSentPositiveError`() {
        val negativeOnly = doubleEntry.account("a_negative_only_acc")

        val error =
            shouldThrow<AccountWouldBeSentPositiveException> {
                lines.insert(negativeOnly, negativeOnly, "test_code", Money(1, usd), Money(1, usd))
            }

        error.message shouldBe "#{Account account: a_negative_only_acc scope:  currency: USD}"
        lineCount() shouldBe 0
    }

    @Test
    fun `updating a line checks the balance as saving a new one does`() {
        val positiveOnly = doubleEntry.account("a_positive_only_acc")
        val line = lines.insert(positiveOnly, positiveOnly, "test_code", Money(1, usd), Money(1, usd))

        shouldThrow<AccountWouldBeSentNegativeException> { lines.update(line.copy(balance = Money(-1, usd))) }

        lines.find(line.id).shouldNotBeNull().balance shouldBe Money(1, usd)
    }

    @Test
    fun `a balance of zero is valid for a positive-only and a negative-only account`() {
        val positiveOnly = doubleEntry.account("a_positive_only_acc")
        val negativeOnly = doubleEntry.account("a_negative_only_acc")

        lines.insert(positiveOnly, positiveOnly, "test_code", Money(-1, usd), zero())
        lines.insert(negativeOnly, negativeOnly, "test_code", Money(1, usd), zero())

        lineCount() shouldBe 2
    }

    /** line_spec.rb:8-24: `line_to_persist.save!` then `reload`, the line read back by its id. */
    private fun persisted(
        account: AccountInstance = this.account,
        partnerAccount: AccountInstance = this.partnerAccount,
        code: String = "test_code",
        amount: Money = tenDollars(),
        balance: Money = zero(),
    ): Line {
        val saved = lines.insert(account, partnerAccount, code, amount, balance)
        return lines.find(saved.id).shouldNotBeNull()
    }

    private fun tenDollars(): Money = Money(10_00, usd)

    private fun zero(): Money = Money.zero(usd)

    private fun lineCount(): Long = transaction(database) { LinesTable.of(jsonMetadata = false).selectAll().count() }
}
