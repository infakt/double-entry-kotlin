package pl.infakt.doubleentry

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import java.util.Currency
import kotlin.io.path.readLines
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.schema.DoubleEntrySchema
import pl.infakt.doubleentry.support.TestDatabase

/**
 * The Usage snippet of README.md, run on a new database of the tests' PostgreSQL: the configuration, the schema, a
 * transfer, a balance, a lock and the line check with the account fixer. Markdown does not compile, so the test body
 * repeats the snippet and checks that the snippet still has its calls.
 */
class ReadmeExampleTest {
    private val database: Database = TestDatabase.empty()

    init {
        DoubleEntrySchema.create(database, jsonMetadata = false)
    }

    private val doubleEntry: DoubleEntry =
        DoubleEntry(database) {
            defaultCurrency = Currency.getInstance("EUR")
            accounts {
                define("cash")
                define("wallet", scopeIdentifier = { customerId -> customerId }, positiveOnly = true)
                define("fees", scopeIdentifier = { customerId -> customerId })
            }
            transfers {
                define(from = "cash", to = "wallet", code = "top_up")
                define(from = "wallet", to = "fees", code = "fee")
            }
        }

    @Test
    fun `the readme snippet transfers, balances, locks and checks the lines`() {
        val cash = doubleEntry.account("cash")
        val wallet = doubleEntry.account("wallet", scope = 42L)
        val fees = doubleEntry.account("fees", scope = 42L)

        val (credit, debit) = doubleEntry.transfer(Money.of(100_00, "EUR"), from = cash, to = wallet, code = "top_up")

        credit.account shouldBe cash
        credit.amount shouldBe Money.of(-100_00, "EUR")
        debit.account shouldBe wallet
        debit.amount shouldBe Money.of(100_00, "EUR")
        val balance = doubleEntry.balance(wallet)
        balance shouldBe Money.of(100_00, "EUR")
        balance.toString() shouldBe "100.00"
        wallet.balance() shouldBe balance

        doubleEntry.lockAccounts(wallet, fees) {
            if (wallet.balance() >= Money.of(5_00, "EUR")) {
                doubleEntry.transfer(Money.of(5_00, "EUR"), from = wallet, to = fees, code = "fee")
            }
        }

        doubleEntry.balance(wallet) shouldBe Money.of(95_00, "EUR")
        doubleEntry.balance("fees", scope = 42L) shouldBe Money.of(5_00, "EUR")
        doubleEntry.balance(cash) shouldBe Money.of(-100_00, "EUR")
        val check = doubleEntry.lineCheck.perform(doubleEntry.accountFixer)
        check.shouldNotBeNull()
        check.errorsFound shouldBe false
    }

    @Test
    fun `the readme's line check with the account fixer corrects a wrong running balance`() {
        val cash = doubleEntry.account("cash")
        val wallet = doubleEntry.account("wallet", scope = 42L)
        val (_, debit) = doubleEntry.transfer(Money.of(100_00, "EUR"), from = cash, to = wallet, code = "top_up")
        transaction(database) {
            exec("UPDATE double_entry_lines SET balance = balance + 1 WHERE id = ${debit.id}")
        }

        val check = doubleEntry.lineCheck.perform(doubleEntry.accountFixer)

        check.shouldNotBeNull()
        check.errorsFound shouldBe true
        check.log.shouldNotBeNull() shouldContain "Error on line #${debit.id}:"
        doubleEntry.lines
            .find(debit.id)
            .shouldNotBeNull()
            .balance shouldBe Money.of(100_00, "EUR")
    }

    @Test
    fun `the readme snippet has the calls this test runs`() {
        val snippet = kotlinBlock(Path.of("README.md").readLines())

        SNIPPET_CALLS.filterNot { it in snippet }.shouldBeEmpty()
    }

    private companion object {
        /** The lines of the test body that the README's snippet has as is. */
        val SNIPPET_CALLS =
            listOf(
                "DoubleEntrySchema.create(database, jsonMetadata = false)",
                "DoubleEntry(database) {",
                "defaultCurrency = Currency.getInstance(\"EUR\")",
                "define(\"cash\")",
                "define(\"wallet\", scopeIdentifier = { customerId -> customerId }, positiveOnly = true)",
                "define(\"fees\", scopeIdentifier = { customerId -> customerId })",
                "define(from = \"cash\", to = \"wallet\", code = \"top_up\")",
                "define(from = \"wallet\", to = \"fees\", code = \"fee\")",
                "val cash = doubleEntry.account(\"cash\")",
                "val wallet = doubleEntry.account(\"wallet\", scope = 42L)",
                "val fees = doubleEntry.account(\"fees\", scope = 42L)",
                "doubleEntry.transfer(Money.of(100_00, \"EUR\"), from = cash, to = wallet, code = \"top_up\")",
                "val balance = doubleEntry.balance(wallet)",
                "doubleEntry.lockAccounts(wallet, fees) {",
                "if (wallet.balance() >= Money.of(5_00, \"EUR\")) {",
                "doubleEntry.transfer(Money.of(5_00, \"EUR\"), from = wallet, to = fees, code = \"fee\")",
                "val check = doubleEntry.lineCheck.perform(doubleEntry.accountFixer)",
            )

        /** The first ```kotlin block of a Markdown file. */
        fun kotlinBlock(lines: List<String>): String =
            lines
                .dropWhile { it.trim() != "```kotlin" }
                .drop(1)
                .takeWhile { it.trim() != "```" }
                .joinToString("\n")
    }
}
