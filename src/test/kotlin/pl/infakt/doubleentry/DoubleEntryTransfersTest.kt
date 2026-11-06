package pl.infakt.doubleentry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.util.Currency
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.schema.AccountBalancesTable
import pl.infakt.doubleentry.schema.LinesTable
import pl.infakt.doubleentry.support.TestDatabase

/** double_entry_spec.rb:91-164: transfers through the facade, BHD in place of BTC (X-02). */
class DoubleEntryTransfersTest {
    private val database: Database = TestDatabase.ledger(jsonMetadata = false)

    private val usd: Currency = Currency.getInstance("USD")

    // double_entry_spec.rb:92-106.
    private val doubleEntry =
        DoubleEntry(database) {
            accounts {
                define("savings")
                define("cash")
                define("trash")
                define("bitbucket", currency = Currency.getInstance("BHD"))
            }
            transfers {
                define("savings", "cash", "xfer")
                define("trash", "bitbucket", "mismatch_xfer")
            }
        }

    private val savings = doubleEntry.account("savings")
    private val cash = doubleEntry.account("cash")
    private val trash = doubleEntry.account("trash")
    private val bitbucket = doubleEntry.account("bitbucket")

    private val amount = Money(100_00, usd)

    @Test
    fun `transfers can transfer from an account to an account, if the transfer is allowed`() {
        val (credit, debit) = doubleEntry.transfer(amount, from = savings, to = cash, code = "xfer")

        credit.account shouldBe savings
        credit.amount shouldBe Money(-100_00, usd)
        debit.account shouldBe cash
        debit.amount shouldBe amount
        lineCount() shouldBe 2
    }

    @Test
    fun `transfers raises an exception when the transfer is not allowed (wrong direction)`() {
        val error =
            shouldThrow<TransferNotAllowedException> {
                doubleEntry.transfer(amount, from = cash, to = savings, code = "xfer")
            }

        error.message shouldBe "[:cash, :savings, :xfer]"
        lineCount() shouldBe 0
        balanceCount() shouldBe 0
    }

    @Test
    fun `transfers raises an exception when the transfer is not allowed (wrong code)`() {
        val error =
            shouldThrow<TransferNotAllowedException> {
                doubleEntry.transfer(amount, from = savings, to = cash, code = "yfer")
            }

        error.message shouldBe "[:savings, :cash, :yfer]"
    }

    @Test
    fun `transfers raises an exception when the transfer is not allowed (does not exist, at all)`() {
        val error = shouldThrow<TransferNotAllowedException> { doubleEntry.transfer(amount, from = cash, to = trash) }

        error.message shouldBe "[:cash, :trash, nil]"
    }

    @Test
    fun `transfers raises an exception when the transfer is not allowed (mismatched currencies)`() {
        val error =
            shouldThrow<MismatchedCurrenciesException> {
                doubleEntry.transfer(amount, from = trash, to = bitbucket, code = "mismatch_xfer")
            }

        error.message shouldBe "Mismatched currency (BHD <> USD)"
        lineCount() shouldBe 0
        balanceCount() shouldBe 0
    }

    private fun lineCount(): Long = transaction(database) { LinesTable.of(jsonMetadata = false).selectAll().count() }

    private fun balanceCount(): Long = transaction(database) { AccountBalancesTable.selectAll().count() }
}
