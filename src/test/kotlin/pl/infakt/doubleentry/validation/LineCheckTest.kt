package pl.infakt.doubleentry.validation

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.Currency
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.AccountInstance
import pl.infakt.doubleentry.DoubleEntry
import pl.infakt.doubleentry.Line
import pl.infakt.doubleentry.Money
import pl.infakt.doubleentry.schema.AccountBalancesTable
import pl.infakt.doubleentry.support.SpecAccounts
import pl.infakt.doubleentry.support.TestDatabase
import pl.infakt.doubleentry.support.TestUser

/**
 * line_check_spec.rb: `LineCheck.last_line_id_checked` and `LineCheck#perform`, through `DoubleEntry.lineCheck`, on
 * the spec accounts (`support/SpecAccounts.kt`). The examples that correct a balance with an `AccountFixer`
 * (line_check_spec.rb:60, 82, 108) belong to the account-fixer stage; here the fixer is `null`, or a fixer that only
 * records the accounts it is handed. The BTC examples use BHD (X-02).
 */
class LineCheckTest {
    private val database: Database = TestDatabase.ledger(jsonMetadata = false)

    private val usd: Currency = Currency.getInstance("USD")

    private val bhd: Currency = Currency.getInstance("BHD")

    /** The frozen time, moved as the spec's `Timecop.freeze` moves it. */
    private var now: Instant = Instant.parse("2024-01-01T00:00:00Z")

    private val doubleEntry: DoubleEntry =
        SpecAccounts.doubleEntry(database) {
            clock =
                object : Clock {
                    override fun now(): Instant = this@LineCheckTest.now
                }
        }

    private val lineCheck: LineCheck = doubleEntry.lineCheck

    private val user = TestUser(1)

    /** The accounts the fixer was handed, in order. */
    private val recalculated = mutableListOf<AccountInstance>()

    /** Stands in for the spec's `AccountFixer.new`: it records the accounts and corrects nothing. */
    private val fixer = AccountRecalculator { account -> recalculated += account }

    @Test
    fun `should find the newest LineCheck created (by creation_date)`() {
        val start = now
        now = start - 3.minutes
        lineCheck.create(lastLineId = 100, errorsFound = false, log = "")
        now = start - 1.minutes
        lineCheck.create(lastLineId = 300, errorsFound = false, log = "")
        now = start - 2.minutes
        lineCheck.create(lastLineId = 200, errorsFound = false, log = "")
        now = start

        lineCheck.lastLineIdChecked() shouldBe 300
    }

    @Test
    fun `a user with 100 dollars, all consistent, all lines checked, it should be nil`() {
        savingsBalance(100_00)
        lineCheck.perform(fixer)

        lineCheck.perform(fixer).shouldBeNull()
    }

    @Test
    fun `a user with 100 dollars, all consistent, all lines checked, should not persist a new LineCheck`() {
        savingsBalance(100_00)
        lineCheck.perform(fixer)
        val count = lineCheck.records().size

        lineCheck.perform(fixer)

        lineCheck.records().size shouldBe count
    }

    @Test
    fun `a user with 100 dollars, all consistent, it should be an instance of LineCheck`() {
        savingsBalance(100_00)

        lineCheck.perform(fixer).shouldBeInstanceOf<LineCheckRecord>()
    }

    @Test
    fun `a user with 100 dollars, all consistent, errors_found should eq false`() {
        savingsBalance(100_00)

        lineCheck.perform(fixer).shouldNotBeNull().errorsFound shouldBe false
    }

    @Test
    fun `a user with 100 dollars, all consistent, should persist the LineCheck`() {
        savingsBalance(100_00)

        val check = lineCheck.perform(fixer).shouldNotBeNull()

        // Active Record's `==` compares records by id.
        lineCheck.records().last().id shouldBe check.id
    }

    @Test
    fun `a consistency error in lines, errors_found should be true`() {
        savingsBalance(100_00)
        corruptFirstLine()

        lineCheck.perform(fixer).shouldNotBeNull().errorsFound shouldBe true
    }

    @Test
    fun `a consistency error in lines, the log should match Error on line`() {
        savingsBalance(100_00)
        corruptFirstLine()

        val log = lineCheck.perform(fixer).shouldNotBeNull().log

        log.shouldNotBeNull() shouldContain Regex("Error on line")
    }

    @Test
    fun `a consistency error in lines, given no fixer, should not correct the running balance`() {
        savingsBalance(100_00)
        corruptFirstLine()
        val before = firstLine().balance

        lineCheck.perform(fixer = null)

        firstLine().balance shouldBe before
    }

    @Test
    fun `a consistency error in account balance, errors_found should be true`() {
        savingsBalance(100_00)
        corruptFirstAccountBalance()

        lineCheck.perform(fixer).shouldNotBeNull().errorsFound shouldBe true
    }

    @Test
    fun `a consistency error in account balance, the log should include the error on the account in USD (X-05)`() {
        savingsBalance(100_00)
        corruptFirstAccountBalance()

        val check = lineCheck.perform(fixer).shouldNotBeNull()

        check.log.shouldNotBeNull() shouldContain
            "Error on account #{Account account: savings scope:  currency: USD}: " +
            "100.01 (cached balance) != 100.00 (running balance)\n"
    }

    @Test
    fun `a consistency error in account balance, given no fixer, should not correct the account balance`() {
        savingsBalance(100_00)
        corruptFirstAccountBalance()
        val before = firstAccountBalance()

        lineCheck.perform(fixer = null)

        firstAccountBalance() shouldBe before
    }

    @Test
    fun `a user with a non default currency balance, errors_found should eq false`() {
        bhdBalance(100_00)

        lineCheck.perform(fixer).shouldNotBeNull().errorsFound shouldBe false
    }

    @Test
    fun `a non default currency balance with a consistency error in lines, errors_found should eq true`() {
        bhdBalance(100_00)
        corruptFirstLine()

        lineCheck.perform(fixer).shouldNotBeNull().errorsFound shouldBe true
    }

    @Test
    fun `a non default currency balance with a line error, the log should include the error on the account`() {
        bhdBalance(100_00)
        corruptFirstLine()

        val check = lineCheck.perform(fixer).shouldNotBeNull()

        check.log.shouldNotBeNull() shouldContain
            "Error on account #{Account account: bhd_test scope:  currency: BHD}: " +
            "-10.000 (cached balance) != -9.999 (running balance)\n"
    }

    @Test
    fun `a non default currency balance with a line error, given no fixer, should not correct the running balance`() {
        bhdBalance(100_00)
        corruptFirstLine()
        val before = firstLine().balance

        lineCheck.perform(fixer = null)

        firstLine().balance shouldBe before
    }

    @Test
    fun `the last line id checked is 0 without a check`() {
        lineCheck.lastLineIdChecked() shouldBe 0
    }

    @Test
    fun `an error on a line ends with the previous line and the line as Kotlin writes them (X-12)`() {
        savingsBalance(100_00)
        savingsBalance(100_00)
        corruptLine(3)
        val first = doubleEntry.lines.find(1).shouldNotBeNull()
        val third = doubleEntry.lines.find(3).shouldNotBeNull()

        val check = lineCheck.perform(fixer = null).shouldNotBeNull()

        check.log shouldBe
            "*********************************\n" +
            "Error on line #3: balance:-199.99 != -100.00 + -100.00\n" +
            "*********************************\n" +
            "  [$first]\n" +
            "  $third\n" +
            "\n" +
            "*********************************\n" +
            "Error on account #{Account account: test scope:  currency: USD}: " +
            "-200.00 (cached balance) != -199.99 (running balance)\n" +
            "*********************************\n" +
            "\n"
    }

    @Test
    fun `an error on the first line of an account shows no previous line, as an empty list (X-12)`() {
        savingsBalance(100_00)
        corruptFirstLine()

        val check = lineCheck.perform(fixer = null).shouldNotBeNull()

        check.log.shouldNotBeNull() shouldContain
            "Error on line #1: balance:-99.99 != 0.00 + -100.00\n" +
            "*********************************\n" +
            "  []\n" +
            "  ${firstLine()}\n" +
            "\n"
    }

    @Test
    fun `the check is written with the last line id and the log, its times the clock's`() {
        savingsBalance(100_00)
        corruptFirstAccountBalance()

        val check = lineCheck.perform(fixer = null).shouldNotBeNull()

        check.lastLineId shouldBe 2
        check.createdAt shouldBe now
        check.updatedAt shouldBe now
        lineCheck.records().single().let { stored ->
            stored.lastLineId shouldBe 2
            stored.errorsFound shouldBe true
            stored.log shouldBe check.log
        }
        lineCheck.lastLineIdChecked() shouldBe 2
    }

    @Test
    fun `the fixer is handed each incorrect account once, in the order they were found`() {
        savingsBalance(100_00)
        corruptFirstLine()
        corruptFirstAccountBalance()

        lineCheck.perform(fixer)

        // test: its line and its record; savings: its record.
        recalculated shouldBe listOf(account("test"), account("savings"))
    }

    @Test
    fun `the fixer is handed nothing when all is consistent`() {
        savingsBalance(100_00)

        lineCheck.perform(fixer)

        recalculated shouldBe emptyList()
    }

    @Test
    fun `the previous line of an unscoped account is its last line without a scope`() {
        val unscoped =
            DoubleEntry(database) {
                clock = doubleEntry.configuration.clock
                accounts {
                    define("cash")
                    define("bank")
                }
                transfers { define("bank", "cash", "deposit") }
            }
        repeat(2) {
            unscoped.transfer(
                Money(5_00, usd),
                from = unscoped.account("bank"),
                to = unscoped.account("cash"),
                code = "deposit",
            )
        }

        val check = unscoped.lineCheck.perform().shouldNotBeNull()

        check.errorsFound shouldBe false
        check.log shouldBe ""
    }

    @Test
    fun `the lines are read in batches, each after the last id of the one before`() {
        savingsBalance(100_00)
        savingsBalance(100_00)
        corruptLine(4)
        val batched = LineCheck(database, doubleEntry.configuration, doubleEntry.lines, doubleEntry.locking, 3)

        val check = batched.perform().shouldNotBeNull()

        check.lastLineId shouldBe 4
        check.log.shouldNotBeNull() shouldContain "Error on line #4: "
    }

    /** `create(:user, savings_balance: Money.new(cents))`: factories.rb:16-23, a bonus from test to savings. */
    private fun savingsBalance(cents: Long) {
        doubleEntry.transfer(Money(cents, usd), from = account("test"), to = account("savings"), code = "bonus")
    }

    /** `create(:user, bitcoin_balance: Money.new(cents, 'BTC'))`: factories.rb:32-39, in BHD (X-02). */
    private fun bhdBalance(cents: Long) {
        doubleEntry.transfer(
            Money(cents, bhd),
            from = account("bhd_test"),
            to = account("bhd_savings"),
            code = "bhd_test_transfer",
        )
    }

    private fun account(identifier: String): AccountInstance = doubleEntry.account(identifier, scope = user)

    /** `DoubleEntry::Line.order(:id).limit(1).update_all('balance = balance + 1')` (line_check_spec.rb:55, 100). */
    private fun corruptFirstLine() {
        corruptLine(firstLine().id)
    }

    /** Adds 1 to the balance of the line [id], past the library, as `update_all` does. */
    private fun corruptLine(id: Long) {
        transaction(database) { exec("UPDATE double_entry_lines SET balance = balance + 1 WHERE id = $id") }
    }

    /** `DoubleEntry::AccountBalance.order(:id).limit(1).update_all('balance = balance + 1')` (line_check_spec.rb:74). */
    private fun corruptFirstAccountBalance() {
        transaction(database) {
            exec(
                "UPDATE double_entry_account_balances SET balance = balance + 1 " +
                    "WHERE id = (SELECT MIN(id) FROM double_entry_account_balances)",
            )
        }
    }

    private fun firstLine(): Line = doubleEntry.lines.withIdGreaterThan(0).first()

    /** The balance column of the first balance record by id, `AccountBalance.order(:id).first.balance`. */
    private fun firstAccountBalance(): Long =
        transaction(database) {
            val table = AccountBalancesTable
            table
                .selectAll()
                .orderBy(table.id)
                .first()[table.balance]
        }
}
