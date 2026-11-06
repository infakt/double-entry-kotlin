package pl.infakt.doubleentry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.Currency
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.schema.AccountBalancesTable
import pl.infakt.doubleentry.schema.LinesTable
import pl.infakt.doubleentry.support.TestDatabase

/** double_entry_spec.rb:166-229: the lines of a transfer from `a` to `b`, read back through `DoubleEntry.lines`. */
class DoubleEntryLinesTest {
    private val database: Database = TestDatabase.ledger(jsonMetadata = false)

    private val usd: Currency = Currency.getInstance("USD")

    // double_entry_spec.rb:168-177, and the positive-only, negative-only and scoped accounts of the port-only tests.
    private val doubleEntry =
        DoubleEntry(database) {
            accounts {
                define("a")
                define("b")
                define("positive_only", positiveOnly = true)
                define("negative_only", negativeOnly = true)
                define("test", scopeIdentifier = { scope -> scope })
            }
            transfers {
                define("a", "b", "xfer")
                define("positive_only", "b", "xfer")
                define("a", "negative_only", "xfer")
            }
        }

    private val lines = doubleEntry.lines

    private val accountA = doubleEntry.account("a")
    private val accountB = doubleEntry.account("b")

    /** `lines_for_account(account_a).first` (double_entry_spec.rb:184). */
    private val creditLine: Line
        get() = lines.forAccount(accountA).first()

    /** `lines_for_account(account_b).first` (double_entry_spec.rb:185). */
    private val debitLine: Line
        get() = lines.forAccount(accountB).first()

    // double_entry_spec.rb:179.
    @BeforeEach
    fun transfer() {
        doubleEntry.transfer(Money(10_00, usd), from = accountA, to = accountB, code = "xfer")
    }

    @Test
    fun `lines has an amount`() {
        creditLine.amount shouldBe Money(-10_00, usd)
        debitLine.amount shouldBe Money(10_00, usd)
    }

    @Test
    fun `lines has a code`() {
        creditLine.code shouldBe "xfer"
        debitLine.code shouldBe "xfer"
    }

    @Test
    fun `lines auto-sets scope when assigning account (and partner_accout, is this implementation)`() {
        val table = LinesTable.of(jsonMetadata = false)
        val row =
            transaction(database) {
                table.selectAll().where { table.id eq creditLine.id }.single()
            }

        row[table.account] shouldBe "a"
        row[table.scope] shouldBe null
        row[table.partnerAccount] shouldBe "b"
        row[table.partnerScope] shouldBe null
    }

    @Test
    fun `lines has a partner_account (or is this implementation)`() {
        creditLine.partnerAccount shouldBe debitLine.account
    }

    @Test
    fun `lines knows if it is an increase or decrease`() {
        creditLine.isDecrease shouldBe true
        debitLine.isIncrease shouldBe true
        creditLine.isIncrease shouldBe false
        debitLine.isDecrease shouldBe false
    }

    @Test
    fun `lines can reference its partner`() {
        lines.partnerOf(creditLine) shouldBe debitLine
        lines.partnerOf(debitLine) shouldBe creditLine
    }

    @Test
    fun `lines can ask for its pair (credit always coming first)`() {
        lines.pairOf(creditLine) shouldBe (creditLine to debitLine)
        lines.pairOf(debitLine) shouldBe (creditLine to debitLine)
    }

    @Test
    fun `lines can ask for the account (and get an instance)`() {
        creditLine.account shouldBe accountA
        debitLine.account shouldBe accountB
    }

    @Test
    fun `credits lists the lines of a positive amount and debits those of a negative one (U-07)`() {
        lines.credits() shouldBe listOf(debitLine)
        lines.debits() shouldBe listOf(creditLine)
    }

    @Test
    fun `withIdGreaterThan lists the lines of a greater id, by id`() {
        doubleEntry.transfer(Money(1_00, usd), from = accountA, to = accountB, code = "xfer")

        lines.withIdGreaterThan(creditLine.id).map { it.id } shouldBe listOf(2L, 3L, 4L)
        lines.withIdGreaterThan(4L) shouldBe emptyList()
    }

    @Test
    fun `forAccount lists the lines of the account, by id`() {
        doubleEntry.transfer(Money(1_00, usd), from = accountA, to = accountB, code = "xfer")

        lines.forAccount(accountA).map { it.amount } shouldBe listOf(Money(-10_00, usd), Money(-1_00, usd))
        lines.forAccount(accountB).map { it.balance } shouldBe listOf(Money(10_00, usd), Money(11_00, usd))
    }

    @Test
    fun `forAccount of a scoped account lists only the lines of its scope identity, by id`() {
        val scope17 = doubleEntry.account("test", scopeIdentity = "17")
        val scope72 = doubleEntry.account("test", scopeIdentity = "72")
        val first = lines.insert(scope17, scope72, "xfer", Money(1_00, usd), Money(1_00, usd))
        lines.insert(scope72, scope17, "xfer", Money(2_00, usd), Money(2_00, usd))
        val second = lines.insert(scope17, scope72, "xfer", Money(3_00, usd), Money(4_00, usd))

        lines.forAccount(scope17).map { it.id } shouldBe listOf(first.id, second.id)
        lines.forAccount(scope17).map { it.account } shouldBe listOf(scope17, scope17)
        lines.forAccount(scope72).map { it.amount } shouldBe listOf(Money(2_00, usd))
        lines.forAccount(doubleEntry.account("test", scopeIdentity = "54")) shouldBe emptyList()
    }

    @Test
    fun `pairOf puts a line of zero second, after its partner`() {
        val partner = lines.insert(accountB, accountA, "xfer", Money(1, usd), Money(1, usd))
        val zero = lines.insert(accountA, accountB, "xfer", Money.zero(usd), Money.zero(usd), partnerId = partner.id)

        zero.isDecrease shouldBe false
        // Both read back, as pairOf reads them: the stored timestamps keep microseconds, the returned ones may not.
        val storedPartner = lines.find(partner.id).shouldNotBeNull()
        val storedZero = lines.find(zero.id).shouldNotBeNull()
        lines.pairOf(zero) shouldBe (storedPartner to storedZero)
    }

    @Test
    fun `a line without a partner has no partner and no pair`() {
        val line = lines.insert(accountA, accountB, "xfer", Money(1, usd), Money(1, usd))

        lines.partnerOf(line) shouldBe null
        lines.pairOf(line) shouldBe null
    }

    @Test
    fun `a transfer that would take a positive-only account below zero writes nothing`() {
        val from = doubleEntry.account("positive_only")

        shouldThrow<AccountWouldBeSentNegativeException> {
            doubleEntry.transfer(Money(1, usd), from = from, to = accountB, code = "xfer")
        }

        // The balance record is created before the lock, as locking.rb:162-170 does; only its update rolls back.
        lines.withIdGreaterThan(2L) shouldBe emptyList()
        storedBalance("positive_only") shouldBe 0L
        storedBalance("b") shouldBe 10_00L
    }

    @Test
    fun `a transfer that would take a negative-only account above zero writes nothing`() {
        val to = doubleEntry.account("negative_only")

        shouldThrow<AccountWouldBeSentPositiveException> {
            doubleEntry.transfer(Money(1, usd), from = accountA, to = to, code = "xfer")
        }

        lines.withIdGreaterThan(2L) shouldBe emptyList()
        storedBalance("negative_only") shouldBe 0L
        storedBalance("a") shouldBe -10_00L
    }

    /** The stored balance of the unscoped account [identifier], or `null` without a balance record. */
    private fun storedBalance(identifier: String): Long? =
        transaction(database) {
            AccountBalancesTable
                .selectAll()
                .where { AccountBalancesTable.account eq identifier }
                .singleOrNull()
                ?.get(AccountBalancesTable.balance)
        }
}
