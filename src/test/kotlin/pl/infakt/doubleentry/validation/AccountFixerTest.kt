package pl.infakt.doubleentry.validation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.Currency
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.AccountInstance
import pl.infakt.doubleentry.DoubleEntry
import pl.infakt.doubleentry.Line
import pl.infakt.doubleentry.LockMustBeOutermostTransactionException
import pl.infakt.doubleentry.Money
import pl.infakt.doubleentry.schema.AccountBalancesTable
import pl.infakt.doubleentry.support.SpecAccounts
import pl.infakt.doubleentry.support.TestDatabase
import pl.infakt.doubleentry.support.TestUser

/**
 * The examples of line_check_spec.rb that correct a balance with `AccountFixer.new` (line_check_spec.rb:60, 82, 108),
 * through `DoubleEntry.lineCheck.perform(DoubleEntry.accountFixer)`, on the spec accounts (`support/SpecAccounts.kt`),
 * and the port's tests of `AccountFixer#recalculate_account` (validation/account_fixer.rb). The BTC example uses BHD
 * (X-02).
 */
class AccountFixerTest {
    private val database: Database = TestDatabase.ledger(jsonMetadata = false)

    private val usd: Currency = Currency.getInstance("USD")

    private val bhd: Currency = Currency.getInstance("BHD")

    /** The frozen time, which a test may move. */
    private var now: Instant = Instant.parse("2024-01-01T00:00:00Z")

    private val doubleEntry: DoubleEntry =
        SpecAccounts.doubleEntry(database) {
            clock =
                object : Clock {
                    override fun now(): Instant = this@AccountFixerTest.now
                }
        }

    private val lineCheck: LineCheck = doubleEntry.lineCheck

    private val fixer: AccountFixer = doubleEntry.accountFixer

    private val user = TestUser(1)

    @Test
    fun `a consistency error in lines, should correct the running balance`() {
        savingsBalance(100_00)
        corruptLine(firstLine().id)
        val before = firstLine().balance

        lineCheck.perform(fixer)

        firstLine().balance shouldBe before + Money(-1, usd)
    }

    @Test
    fun `a consistency error in account balance, should correct the account balance`() {
        savingsBalance(100_00)
        corruptFirstAccountBalance()
        val before = firstAccountBalance()

        lineCheck.perform(fixer)

        firstAccountBalance() shouldBe before - 1
    }

    @Test
    fun `a non default currency balance with a line error, should correct the running balance`() {
        bhdBalance(100_00)
        corruptLine(firstLine().id)
        val before = firstLine().balance

        lineCheck.perform(fixer)

        firstLine().balance shouldBe before + Money(-1, bhd)
    }

    @Test
    fun `only the lines whose running balance differs are written, and the balance record is set`() {
        savingsBalance(100_00)
        savingsBalance(100_00)
        corruptLine(1)
        corruptAccountBalance("test")
        val start = now
        now = start + 1.minutes

        fixer.recalculateAccount(account("test"))

        // Lines 1 and 3 are test's: line 1 is one too high, line 3 is right.
        doubleEntry.lines.find(1).shouldNotBeNull().let { line ->
            line.balance shouldBe Money(-100_00, usd)
            line.updatedAt shouldBe now
        }
        doubleEntry.lines.find(3).shouldNotBeNull().let { line ->
            line.balance shouldBe Money(-200_00, usd)
            line.updatedAt shouldBe start
        }
        doubleEntry.accountBalances
            .findByAccount(account("test"))
            .shouldNotBeNull()
            .balance shouldBe
            Money(-200_00, usd)
        balanceRecordUpdatedAt("test") shouldBe now
    }

    @Test
    fun `a balance record that is right is not written`() {
        savingsBalance(100_00)
        val start = now
        now = start + 1.minutes

        fixer.recalculateAccount(account("test"))

        doubleEntry.accountBalances
            .findByAccount(account("test"))
            .shouldNotBeNull()
            .balance shouldBe
            Money(-100_00, usd)
        balanceRecordUpdatedAt("test") shouldBe start
        doubleEntry.lines
            .find(1)
            .shouldNotBeNull()
            .updatedAt shouldBe start
    }

    @Test
    fun `another scope's lines and balance record are left alone`() {
        val other = TestUser(2)
        savingsBalance(100_00)
        savingsBalance(100_00, other)
        savingsBalance(100_00)
        corruptLine(1)
        val start = now
        now = start + 1.minutes

        fixer.recalculateAccount(account("test"))

        // Lines 1 and 5 are user 1's test lines, line 3 is user 2's.
        doubleEntry.lines
            .find(1)
            .shouldNotBeNull()
            .balance shouldBe Money(-100_00, usd)
        doubleEntry.lines
            .find(5)
            .shouldNotBeNull()
            .balance shouldBe Money(-200_00, usd)
        doubleEntry.lines.find(3).shouldNotBeNull().let { line ->
            line.balance shouldBe Money(-100_00, usd)
            line.updatedAt shouldBe start
        }
        doubleEntry.accountBalances
            .findByAccount(account("test"))
            .shouldNotBeNull()
            .balance shouldBe
            Money(-200_00, usd)
        doubleEntry.accountBalances
            .findByAccount(account("test", other))
            .shouldNotBeNull()
            .balance shouldBe
            Money(-100_00, usd)
        balanceRecordUpdatedAt("test", other) shouldBe start
    }

    @Test
    fun `the lines of an unscoped account are those without a scope`() {
        val unscoped =
            DoubleEntry(database) {
                clock = doubleEntry.configuration.clock
                accounts {
                    define("cash")
                    define("bank")
                }
                transfers { define("bank", "cash", "deposit") }
            }
        // The same identifiers in a scope, whose lines share the table with the unscoped ones.
        val scoped =
            DoubleEntry(database) {
                clock = doubleEntry.configuration.clock
                accounts {
                    define("cash", scopeIdentifier = { scope -> (scope as TestUser).id })
                    define("bank", scopeIdentifier = { scope -> (scope as TestUser).id })
                }
                transfers { define("bank", "cash", "deposit") }
            }
        unscopedDeposit(unscoped, 5_00)
        scoped.transfer(
            Money(7_00, usd),
            from = scoped.account("bank", scope = user),
            to = scoped.account("cash", scope = user),
            code = "deposit",
        )
        unscopedDeposit(unscoped, 5_00)
        corruptLine(1)

        unscoped.accountFixer.recalculateAccount(unscoped.account("bank"))

        // Lines 1 and 5 are the unscoped bank's, line 3 is the scoped bank's.
        unscoped.lines
            .find(1)
            .shouldNotBeNull()
            .balance shouldBe Money(-5_00, usd)
        unscoped.lines
            .find(5)
            .shouldNotBeNull()
            .balance shouldBe Money(-10_00, usd)
        // A scoped line is read through the facade that defines its account as scoped.
        scoped.lines
            .find(3)
            .shouldNotBeNull()
            .balance shouldBe Money(-7_00, usd)
        unscoped.accountBalances
            .findByAccount(unscoped.account("bank"))
            .shouldNotBeNull()
            .balance shouldBe
            Money(-10_00, usd)
        scoped.accountBalances
            .findByAccount(scoped.account("bank", scope = user))
            .shouldNotBeNull()
            .balance shouldBe
            Money(-7_00, usd)
    }

    /** A deposit of [cents] from the unscoped bank to the unscoped cash of [unscoped]. */
    private fun unscopedDeposit(
        unscoped: DoubleEntry,
        cents: Long,
    ) {
        unscoped.transfer(
            Money(cents, usd),
            from = unscoped.account("bank"),
            to = unscoped.account("cash"),
            code = "deposit",
        )
    }

    @Test
    fun `a corrected line keeps its stored json metadata`() {
        val jsonDatabase = TestDatabase.ledger(jsonMetadata = true)
        val json = SpecAccounts.doubleEntry(jsonDatabase) { jsonMetadata = true }
        json.transfer(
            Money(100_00, usd),
            from = json.account("test", scope = user),
            to = json.account("savings", scope = user),
            code = "bonus",
        )
        // A number above Long.MAX_VALUE, which a read-back line holds only as a rounded Double.
        transaction(jsonDatabase) {
            exec(
                "UPDATE double_entry_lines SET balance = balance + 1, " +
                    "metadata = '{\"n\": 12345678901234567890}' WHERE id = 1",
            )
        }
        val before = storedMetadata(jsonDatabase, 1)

        json.accountFixer.recalculateAccount(json.account("test", scope = user))

        json.lines
            .find(1)
            .shouldNotBeNull()
            .balance shouldBe Money(-100_00, usd)
        before shouldContain "12345678901234567890"
        storedMetadata(jsonDatabase, 1) shouldBe before
    }

    @Test
    fun `the fixer locks the account, so it must run outside a transaction`() {
        savingsBalance(100_00)

        transaction(database) {
            shouldThrow<LockMustBeOutermostTransactionException> { fixer.recalculateAccount(account("test")) }
        }
    }

    /** `create(:user, savings_balance: Money.new(cents))`: factories.rb:16-23, a bonus from test to savings. */
    private fun savingsBalance(
        cents: Long,
        owner: TestUser = user,
    ) {
        doubleEntry.transfer(
            Money(cents, usd),
            from = account("test", owner),
            to = account("savings", owner),
            code = "bonus",
        )
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

    private fun account(
        identifier: String,
        owner: TestUser = user,
    ): AccountInstance = doubleEntry.account(identifier, scope = owner)

    /** Adds 1 to the balance of the line [id], past the library, as `update_all('balance = balance + 1')` does. */
    private fun corruptLine(id: Long) {
        transaction(database) { exec("UPDATE double_entry_lines SET balance = balance + 1 WHERE id = $id") }
    }

    /** `AccountBalance.order(:id).limit(1).update_all('balance = balance + 1')` (line_check_spec.rb:74). */
    private fun corruptFirstAccountBalance() {
        transaction(database) {
            exec(
                "UPDATE double_entry_account_balances SET balance = balance + 1 " +
                    "WHERE id = (SELECT MIN(id) FROM double_entry_account_balances)",
            )
        }
    }

    /** Adds 1 to the balance of the record of the account [identifier], past the library. */
    private fun corruptAccountBalance(identifier: String) {
        transaction(database) {
            exec("UPDATE double_entry_account_balances SET balance = balance + 1 WHERE account = '$identifier'")
        }
    }

    /** The `updated_at` of the balance record of the account [identifier] of [owner]. */
    private fun balanceRecordUpdatedAt(
        identifier: String,
        owner: TestUser = user,
    ): Instant =
        transaction(database) {
            val table = AccountBalancesTable
            table
                .selectAll()
                .where { (table.account eq identifier) and (table.scope eq owner.id.toString()) }
                .single()[table.updatedAt]
        }

    /** The stored `metadata` column of the line [id] of [database], as the database prints it. */
    private fun storedMetadata(
        database: Database,
        id: Long,
    ): String? =
        transaction(database) {
            exec("SELECT metadata::text FROM double_entry_lines WHERE id = $id") { result ->
                if (result.next()) result.getString(1) else null
            }
        }

    /** `DoubleEntry::Line.order(:id).first`. */
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
