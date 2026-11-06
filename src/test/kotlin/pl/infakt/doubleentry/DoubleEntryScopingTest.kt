package pl.infakt.doubleentry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.Currency
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.support.TestDatabase
import pl.infakt.doubleentry.support.TestUser

/**
 * double_entry_spec.rb:351-430: scoping of accounts. john is `TestUser(1)` and ryan `TestUser(2)`; the balance
 * records are read back through `DoubleEntry.accountBalances` beside the balances the spec checks.
 */
class DoubleEntryScopingTest {
    private val database: Database = TestDatabase.ledger(jsonMetadata = false)

    private val usd: Currency = Currency.getInstance("USD")

    /** `double(id: 7)` of double_entry_spec.rb:404: an object with an id that is not a user. */
    private data class NotAUser(
        val id: Long,
    )

    // double_entry_spec.rb:352-370.
    private val doubleEntry =
        DoubleEntry(database) {
            val userScope = { user: Any -> (user as? TestUser)?.id ?: throw RuntimeException("not a User") }
            accounts {
                define("bank")
                define("cash", scopeIdentifier = userScope)
                define("savings", scopeIdentifier = userScope)
            }
            transfers {
                define("bank", "cash", "xfer")
                define("cash", "cash", "xfer")
                define("cash", "savings", "xfer")
            }
        }

    // double_entry_spec.rb:372-382.
    private val bank = doubleEntry.account("bank")

    private val john = TestUser(1)
    private val johnsCash = doubleEntry.account("cash", scope = john)

    private val ryan = TestUser(2)
    private val ryansCash = doubleEntry.account("cash", scope = ryan)
    private val ryansSavings = doubleEntry.account("savings", scope = ryan)

    @Test
    fun `treats each separately scoped account having their own separate balances`() {
        doubleEntry.transfer(dollars(20_00), from = bank, to = johnsCash, code = "xfer")
        doubleEntry.transfer(dollars(10_00), from = bank, to = ryansCash, code = "xfer")

        johnsCash.balance() shouldBe dollars(20_00)
        ryansCash.balance() shouldBe dollars(10_00)
        record(johnsCash) shouldBe dollars(20_00)
        record(ryansCash) shouldBe dollars(10_00)
    }

    @Test
    fun `allows transfer between two separately scoped accounts`() {
        doubleEntry.transfer(dollars(10_00), from = ryansCash, to = johnsCash, code = "xfer")

        ryansCash.balance() shouldBe dollars(-10_00)
        johnsCash.balance() shouldBe dollars(10_00)
        record(ryansCash) shouldBe dollars(-10_00)
        record(johnsCash) shouldBe dollars(10_00)
    }

    @Test
    fun `reports balance correctly if called from either account or finances object`() {
        doubleEntry.transfer(dollars(10_00), from = ryansCash, to = johnsCash, code = "xfer")

        ryansCash.balance() shouldBe dollars(-10_00)
        doubleEntry.balance("cash", scope = ryan) shouldBe dollars(-10_00)
    }

    @Test
    fun `raises an exception if you try to scope with an object of differing class to that defined on the account`() {
        val notAUser = NotAUser(7)

        val account = shouldThrow<RuntimeException> { doubleEntry.account("savings", scope = notAUser) }
        val balance = shouldThrow<RuntimeException> { doubleEntry.balance("savings", scope = notAUser) }

        account.message shouldBe "not a User"
        balance.message shouldBe "not a User"
    }

    @Test
    fun `raises exception if you try to transfer between the same account, despite it being scoped`() {
        shouldThrow<TransferNotAllowedException> {
            doubleEntry.transfer(dollars(10_00), from = ryansCash, to = ryansCash, code = "xfer")
        }
    }

    @Test
    fun `allows transfer from one persons account to the same persons other kind of account`() {
        doubleEntry.transfer(dollars(100_00), from = ryansCash, to = ryansSavings, code = "xfer")

        ryansCash.balance() shouldBe dollars(-100_00)
        ryansSavings.balance() shouldBe dollars(100_00)
        record(ryansCash) shouldBe dollars(-100_00)
        record(ryansSavings) shouldBe dollars(100_00)
    }

    @Test
    fun `disallows you to report on scoped accounts globally`() {
        shouldThrow<UnknownAccountException> { doubleEntry.balance("cash") }
    }

    /** The balance of [account]'s record, as `AccountBalance.find_by_account(account).balance`. */
    private fun record(account: AccountInstance): Money =
        doubleEntry.accountBalances
            .findByAccount(account)
            .shouldNotBeNull()
            .balance

    private fun dollars(cents: Long): Money = Money(cents, usd)
}
