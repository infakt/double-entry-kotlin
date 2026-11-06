package pl.infakt.doubleentry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.Currency
import kotlin.time.Clock
import kotlin.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.schema.AccountBalancesTable
import pl.infakt.doubleentry.schema.LinesTable
import pl.infakt.doubleentry.support.SpecAccounts
import pl.infakt.doubleentry.support.TestDatabase
import pl.infakt.doubleentry.support.TestUser

/** transfer_spec.rb:25-48: `Transfer.transfer` on the spec accounts, through `DoubleEntry.transfer`. */
class TransferTest {
    private val database: Database = TestDatabase.ledger(jsonMetadata = false)

    private val usd: Currency = Currency.getInstance("USD")

    private val frozen = Instant.parse("2024-01-01T00:00:00Z")

    private val doubleEntry =
        SpecAccounts.doubleEntry(database) {
            clock =
                object : Clock {
                    override fun now(): Instant = frozen
                }
        }

    private val amount = Money(10_00, usd)

    private val user = TestUser(1)

    private val test = doubleEntry.account("test", scope = user)

    private val savings = doubleEntry.account("savings", scope = user)

    private val lines = LinesTable.of(jsonMetadata = false)

    @Test
    fun `transfer without metadata creates lines`() {
        val before = lineCount()

        doubleEntry.transfer(amount, from = test, to = savings, code = "bonus")

        lineCount() shouldBe before + 2
    }

    @Test
    fun `transfer without metadata returns the credit & debit`() {
        val (credit, debit) = doubleEntry.transfer(amount, from = test, to = savings, code = "bonus")

        credit.amount shouldBe -amount
        debit.amount shouldBe amount
    }

    @Test
    fun `the credit and the debit are each other's partners and carry the accounts' balances after the transfer`() {
        val (credit, debit) = doubleEntry.transfer(amount, from = test, to = savings, code = "bonus")

        credit.account shouldBe test
        credit.partnerAccount shouldBe savings
        credit.partnerId shouldBe debit.id
        credit.balance shouldBe Money(-10_00, usd)
        credit.code shouldBe "bonus"
        debit.account shouldBe savings
        debit.partnerAccount shouldBe test
        debit.partnerId shouldBe credit.id
        debit.balance shouldBe Money(10_00, usd)
        storedPartners() shouldBe listOf(credit.id to debit.id, debit.id to credit.id)
    }

    @Test
    fun `a second transfer runs on from the balances the first left`() {
        doubleEntry.transfer(amount, from = test, to = savings, code = "bonus")

        val (credit, debit) = doubleEntry.transfer(Money(2_50, usd), from = test, to = savings, code = "bonus")

        credit.balance shouldBe Money(-12_50, usd)
        debit.balance shouldBe Money(12_50, usd)
        storedBalances() shouldBe mapOf("savings" to 12_50L, "test" to -12_50L)
    }

    @Test
    fun `the lines and the balances take their timestamps from the configuration's clock`() {
        val (credit, debit) = doubleEntry.transfer(amount, from = test, to = savings, code = "bonus")

        listOf(credit.createdAt, credit.updatedAt, debit.createdAt, debit.updatedAt) shouldBe List(4) { frozen }
        val stamps =
            transaction(database) {
                AccountBalancesTable.selectAll().map { it[AccountBalancesTable.updatedAt] } +
                    lines.selectAll().flatMap { listOf(it[lines.createdAt], it[lines.updatedAt]) }
            }
        stamps shouldBe List(6) { frozen }
    }

    @Test
    fun `a transfer without a detail writes none, one with a detail writes its type and id on both lines`() {
        val (plainCredit, plainDebit) = doubleEntry.transfer(amount, from = test, to = savings, code = "bonus")
        val detail = Detail("Invoice", 7)

        val (credit, debit) = doubleEntry.transfer(amount, from = test, to = savings, code = "bonus", detail = detail)

        plainCredit.detail.shouldBeNull()
        plainDebit.detail.shouldBeNull()
        credit.detail shouldBe detail
        debit.detail shouldBe detail
        val stored =
            transaction(database) {
                lines.selectAll().orderBy(lines.id).map { it[lines.detailType] to it[lines.detailId] }
            }
        stored shouldBe listOf(null to null, null to null, "Invoice" to 7L, "Invoice" to 7L)
    }

    @Test
    fun `a negative amount raises TransferIsNegative before the rule is looked up`() {
        shouldThrow<TransferIsNegativeException> {
            doubleEntry.transfer(Money(-1, usd), from = test, to = savings, code = "no_such_code")
        }

        lineCount() shouldBe 0
    }

    @Test
    fun `a zero amount is transferred`() {
        val (credit, debit) = doubleEntry.transfer(Money.zero(usd), from = test, to = savings, code = "bonus")

        credit.amount shouldBe Money.zero(usd)
        debit.amount shouldBe Money.zero(usd)
        lineCount() shouldBe 2
    }

    @Test
    fun `a transfer from an account to itself raises TransferNotAllowed even when a rule allows it`() {
        val loop =
            DoubleEntry(database) {
                accounts { define("cash", scopeIdentifier = { (it as TestUser).id }) }
                transfers { define("cash", "cash", "xfer") }
            }
        val cash = loop.account("cash", scope = TestUser(2))

        val error =
            shouldThrow<TransferNotAllowedException> { loop.transfer(amount, from = cash, to = cash, code = "xfer") }

        error.message shouldBe "from account and to account are identical"
        lineCount() shouldBe 0
    }

    @Test
    fun `a transfer between two scopes of the same account is allowed`() {
        val loop =
            DoubleEntry(database) {
                accounts { define("cash", scopeIdentifier = { (it as TestUser).id }) }
                transfers { define("cash", "cash", "xfer") }
            }

        val from = loop.account("cash", scope = TestUser(2))
        val to = loop.account("cash", scope = TestUser(1))

        val (credit, debit) = loop.transfer(amount, from = from, to = to, code = "xfer")

        credit.account.scopeIdentity shouldBe "2"
        debit.account.scopeIdentity shouldBe "1"
    }

    @Test
    fun `an amount in another currency than the accounts' raises CurrencyMismatchException and writes no line`() {
        val bhdTest = doubleEntry.account("bhd_test", scope = user)
        val bhdSavings = doubleEntry.account("bhd_savings", scope = user)

        shouldThrow<CurrencyMismatchException> {
            doubleEntry.transfer(amount, from = bhdTest, to = bhdSavings, code = "bhd_test_transfer")
        }

        lineCount() shouldBe 0
    }

    @Test
    fun `a transfer between BHD accounts keeps its amounts in BHD`() {
        val bhdTest = doubleEntry.account("bhd_test", scope = user)
        val bhdSavings = doubleEntry.account("bhd_savings", scope = user)
        val fils = Money.of(1_250, "BHD")

        val (credit, debit) = doubleEntry.transfer(fils, from = bhdTest, to = bhdSavings, code = "bhd_test_transfer")

        credit.amount shouldBe -fils
        debit.balance shouldBe fils
    }

    private fun lineCount(): Long = transaction(database) { lines.selectAll().count() }

    private fun storedPartners(): List<Pair<Long, Long?>> =
        transaction(database) { lines.selectAll().orderBy(lines.id).map { it[lines.id].value to it[lines.partnerId] } }

    private fun storedBalances(): Map<String, Long> =
        transaction(database) {
            val table = AccountBalancesTable
            table.selectAll().associate { it[table.account] to it[table.balance] }
        }
}
