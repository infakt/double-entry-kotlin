package pl.infakt.doubleentry

import io.kotest.matchers.shouldBe
import java.util.Currency
import kotlin.time.Clock
import kotlin.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.schema.LinesTable
import pl.infakt.doubleentry.support.SpecAccounts
import pl.infakt.doubleentry.support.TestDatabase
import pl.infakt.doubleentry.support.TestUser

/**
 * balance_calculator_spec.rb: `BalanceCalculator.calculate` of the spec's `test` account of a user, as behaviour on
 * PostgreSQL. The spec stubs `Line.where` with a null-object double and checks the calls the relation receives; the
 * port has no mocking library (CLAUDE.md), so the lines here are written with running balances that are not the sums
 * of their amounts, and each test checks which lines a balance takes and whether it sums them or reads the running
 * balance of the last one.
 */
class BalanceCalculatorTest {
    private val database: Database = TestDatabase.ledger(jsonMetadata = false)

    private val usd: Currency = Currency.getInstance("USD")

    private var now: Instant = Instant.parse("2014-06-19T00:00:00Z")

    private val doubleEntry =
        SpecAccounts.doubleEntry(database) {
            clock =
                object : Clock {
                    override fun now(): Instant = this@BalanceCalculatorTest.now
                }
            accounts { define("unscoped") }
        }

    private val calculator = BalanceCalculator(database, doubleEntry.configuration)

    // `create(:user)` (balance_calculator_spec.rb:6), and another user whose line no balance of the first may take.
    private val user = TestUser(1)
    private val otherUser = TestUser(2)

    private val account = doubleEntry.account("test", scope = user)

    // '2014-06-19 15:09:18 +1000', '2014-06-19 10:09:18 +1000' and '2014-06-19 20:09:18 +1000' of the spec.
    private val at = Instant.parse("2014-06-19T05:09:18Z")
    private val from = Instant.parse("2014-06-19T00:09:18Z")
    private val to = Instant.parse("2014-06-19T10:09:18Z")

    /**
     * Five lines of the account, by id: before the range, at its start, at `at`, at its end and after it. Their sum is
     * 31_00; each running balance is ten times its amount. The other user's line comes last, at a time before `at`.
     */
    @BeforeEach
    fun writeLines() {
        line(Instant.parse("2014-06-19T00:00:00Z"), "code1", 1_00, 10_00)
        line(from, "code2", 2_00, 20_00)
        line(at, "code1", 4_00, 40_00)
        line(to, "code3", 8_00, 80_00)
        line(Instant.parse("2014-06-19T12:00:00Z"), "code2", 16_00, 160_00)
        line(Instant.parse("2014-06-19T04:00:00Z"), "code1", 32_00, 320_00, scope = otherUser)
    }

    @Test
    fun `before a given created_at date, scopes the lines summed to times before (or at) the given time`() {
        // The running balance of the line created at `at`, the last of the user's lines by then.
        calculator.calculate(account, at = at) shouldBe Money(40_00, usd)
    }

    @Test
    fun `before a given date, when a time range is also specified, ignores the time range when summing the lines`() {
        // Not 14_00, the sum of the lines in the range: the running balance at `at`, nothing summed.
        calculator.calculate(account, from = from, to = to, at = at) shouldBe Money(40_00, usd)
    }

    @Test
    fun `between a given range, scopes the lines summed to times within the given range`() {
        // The amounts of the lines at the range's start, at `at` and at its end: both ends are included.
        calculator.calculate(account, from = from, to = to) shouldBe Money(14_00, usd)
    }

    @Test
    fun `calculate when a single code is provided, scopes the lines summed by the given code`() {
        calculator.calculate(account, code = "code1") shouldBe Money(5_00, usd)
    }

    @Test
    fun `calculate when a list of codes is provided, scopes the lines summed by the given codes`() {
        calculator.calculate(account, codes = listOf("code1", "code2")) shouldBe Money(23_00, usd)
    }

    @Test
    fun `calculate when no codes are provided, does not scope the lines summed by any code`() {
        // Not 31_00, the sum of the amounts: the running balance of the last line, of any code.
        calculator.calculate(account) shouldBe Money(160_00, usd)
    }

    @Test
    fun `a code given beside codes is one more of them`() {
        calculator.calculate(account, code = "code3", codes = listOf("code1")) shouldBe Money(13_00, usd)
    }

    @Test
    fun `a sum over no lines is zero`() {
        calculator.calculate(account, code = "no_such_code") shouldBe Money.zero(usd)
    }

    @Test
    fun `a range without lines sums to zero`() {
        val before = Instant.parse("2014-06-18T00:00:00Z")
        calculator.calculate(account, from = before, to = before) shouldBe Money.zero(usd)
    }

    @Test
    fun `a balance without lines is zero`() {
        calculator.calculate(doubleEntry.account("test", scope = TestUser(3))) shouldBe Money.zero(usd)
    }

    @Test
    fun `a time before every line gives a balance of zero`() {
        calculator.calculate(account, at = Instant.parse("2014-06-18T00:00:00Z")) shouldBe Money.zero(usd)
    }

    @Test
    fun `only from or only to is no range, the balance is the running balance of the last line`() {
        calculator.calculate(account, from = from) shouldBe Money(160_00, usd)
        calculator.calculate(account, to = to) shouldBe Money(160_00, usd)
    }

    @Test
    fun `at with a code or codes sums the lines created at or before at with those codes`() {
        // Not 18_00, every code2 line, nor 40_00, the running balance at `at`: the code2 line at the range's start.
        calculator.calculate(account, at = at, code = "code2") shouldBe Money(2_00, usd)
        // The code1 lines before and at `at`; the code3 line at `to` comes after it. Without `at`, 13_00.
        calculator.calculate(account, at = at, codes = listOf("code1", "code3")) shouldBe Money(5_00, usd)
    }

    @Test
    fun `an unscoped account takes its lines in every scope`() {
        // The gem filters the scope only when the account has a scope identity (balance_calculator.rb:74-76, 93).
        unscopedLine(scope = null, amount = 1_00, balance = 1_00)
        unscopedLine(scope = "7", amount = 2_00, balance = 3_00)
        val unscoped = doubleEntry.account("unscoped")

        calculator.calculate(unscoped) shouldBe Money(3_00, usd)
        calculator.calculate(unscoped, code = "xfer") shouldBe Money(3_00, usd)
    }

    @Test
    fun `the account instance and the facade give the calculator's balance`() {
        account.balance(code = "code1") shouldBe Money(5_00, usd)
        account.balance(at = at) shouldBe Money(40_00, usd)
        doubleEntry.balance(account, from = from, to = to) shouldBe Money(14_00, usd)
        doubleEntry.balance("test", scope = user, codes = listOf("code1", "code2")) shouldBe Money(23_00, usd)
        doubleEntry.balance("test", scopeIdentity = "2") shouldBe Money(320_00, usd)
    }

    /** A line of the `test` account of [scope] at [createdAt], written as `Line#save!` writes it. */
    private fun line(
        createdAt: Instant,
        code: String,
        amount: Long,
        balance: Long,
        scope: TestUser = user,
    ) {
        now = createdAt
        val owner = doubleEntry.account("test", scope = scope)
        val partner = doubleEntry.account("savings", scope = scope)
        doubleEntry.lines.insert(owner, partner, code, Money(amount, usd), Money(balance, usd))
    }

    /**
     * A line of the unscoped account `unscoped` with the scope column [scope], straight to `double_entry_lines`: the
     * port writes a line's scope from its account, so only a raw row gives an unscoped account a scoped line.
     */
    private fun unscopedLine(
        scope: String?,
        amount: Long,
        balance: Long,
    ) {
        val lines = LinesTable.of(jsonMetadata = false)
        transaction(database) {
            lines.insert { row ->
                row[lines.account] = "unscoped"
                row[lines.scope] = scope
                row[lines.code] = "xfer"
                row[lines.amount] = amount
                row[lines.balance] = balance
                row[lines.partnerAccount] = "unscoped"
                row[lines.partnerScope] = null
                row[lines.createdAt] = now
                row[lines.updatedAt] = now
            }
        }
    }
}
