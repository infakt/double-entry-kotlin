package pl.infakt.doubleentry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.Currency
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.support.TestDatabase
import pl.infakt.doubleentry.support.TestUser

/**
 * double_entry_spec.rb:231-349: the balances of six accounts after the transfers of three weeks back and one week
 * ahead. The clock is the configuration's, frozen at a fixed now and moved as the spec moves it with `Timecop.freeze`;
 * the `btc_` accounts are `bhd_` accounts in BHD (docs/deviations.md, X-02).
 */
class DoubleEntryBalancesTest {
    private val database: Database = TestDatabase.ledger(jsonMetadata = false)

    private val usd: Currency = Currency.getInstance("USD")
    private val bhd: Currency = Currency.getInstance("BHD")

    /** `Time.now` of the spec, fixed. */
    private val today: Instant = Instant.parse("2024-01-01T00:00:00Z")

    private var now: Instant = today

    // double_entry_spec.rb:240-259.
    private val doubleEntry =
        DoubleEntry(database) {
            clock =
                object : Clock {
                    override fun now(): Instant = this@DoubleEntryBalancesTest.now
                }
            accounts {
                define("work")
                define("cash")
                define("savings")
                define("store")
                define("bhd_store", currency = bhd)
                define("bhd_wallet", currency = bhd)
            }
            transfers {
                define("work", "cash", "salary")
                define("cash", "savings", "xfer")
                define("savings", "cash", "xfer")
                define("cash", "store", "purchase")
                define("cash", "store", "layby")
                define("cash", "store", "deposit")
                define("bhd_store", "bhd_wallet", "bhd_ex")
            }
        }

    // double_entry_spec.rb:232-237.
    private val work = doubleEntry.account("work")
    private val savings = doubleEntry.account("savings")
    private val cash = doubleEntry.account("cash")
    private val store = doubleEntry.account("store")
    private val bhdStore = doubleEntry.account("bhd_store")
    private val bhdWallet = doubleEntry.account("bhd_wallet")

    private val accountBalances = doubleEntry.accountBalances

    // double_entry_spec.rb:261-287.
    @BeforeEach
    fun transfers() {
        travel(ago(21.days) + 1.days) {
            // got paid from work
            doubleEntry.transfer(dollars(1_000_00), from = work, code = "salary", to = cash)
            // transfer half salary into savings
            doubleEntry.transfer(dollars(500_00), from = cash, code = "xfer", to = savings)
        }

        travel(ago(14.days) + 1.days) {
            // got myself a darth vader helmet
            doubleEntry.transfer(dollars(200_00), from = cash, code = "purchase", to = store)
            // paid off some of my darth vader suit layby (to go with the helmet)
            doubleEntry.transfer(dollars(100_00), from = cash, code = "layby", to = store)
            // put a deposit on the darth vader voice changer module (for the helmet)
            doubleEntry.transfer(dollars(100_00), from = cash, code = "deposit", to = store)
        }

        travel(ago(7.days) + 1.days) {
            // transfer 200 out of savings
            doubleEntry.transfer(dollars(200_00), from = savings, code = "xfer", to = cash)
            // pay the remaining balance on the darth vader voice changer module
            doubleEntry.transfer(dollars(200_00), from = cash, code = "purchase", to = store)
        }

        travel(fromNow(7.days)) {
            // it's the future, man
            doubleEntry.transfer(Money(200_00, bhd), from = bhdStore, code = "bhd_ex", to = bhdWallet)
        }
    }

    @Test
    fun `has the initial balances that we expect`() {
        work.balance() shouldBe dollars(-1_000_00)
        cash.balance() shouldBe dollars(100_00)
        savings.balance() shouldBe dollars(300_00)
        store.balance() shouldBe dollars(600_00)
        bhdWallet.balance() shouldBe Money(200_00, bhd)
    }

    @Test
    fun `should have correct account balance records`() {
        listOf(work, cash, savings, store, bhdWallet).forEach { account ->
            accountBalances.findByAccount(account).shouldNotBeNull().balance shouldBe account.balance()
        }
    }

    @Test
    fun `should have correct account balance currencies`() {
        accountBalances
            .findByAccount(bhdWallet)
            .shouldNotBeNull()
            .balance.currency shouldBe bhd
    }

    @Test
    fun `affects origin and destination balance after transfer`() {
        val savingsBalance = savings.balance()
        val cashBalance = cash.balance()
        val amount = dollars(10_00)

        doubleEntry.transfer(amount, from = savings, code = "xfer", to = cash)

        savings.balance() shouldBe savingsBalance - amount
        cash.balance() shouldBe cashBalance + amount
    }

    @Test
    fun `can be queried at a given point in time`() {
        cash.balance(at = ago(7.days)) shouldBe dollars(100_00)
    }

    @Test
    fun `can be queries between two points in time`() {
        cash.balance(from = ago(21.days), to = ago(14.days)) shouldBe dollars(500_00)
    }

    @Test
    fun `can be queried between two points in time, even in the future`() {
        bhdWallet.balance(from = now, to = fromNow(14.days)) shouldBe Money(200_00, bhd)
    }

    @Test
    fun `can report on balances, scoped by code`() {
        cash.balance(code = "salary") shouldBe dollars(1_000_00)
    }

    @Test
    fun `can report on balances, scoped by many codes`() {
        store.balance(codes = listOf("layby", "deposit")) shouldBe dollars(200_00)
    }

    @Test
    fun `has running balances for each line`() {
        val lines = doubleEntry.lines.forAccount(cash)
        lines[0].balance shouldBe dollars(1_000_00) // salary
        lines[1].balance shouldBe dollars(500_00) // savings
        lines[2].balance shouldBe dollars(300_00) // purchase
        lines[3].balance shouldBe dollars(200_00) // layby
        lines[4].balance shouldBe dollars(100_00) // deposit
        lines[5].balance shouldBe dollars(300_00) // savings
        lines[6].balance shouldBe dollars(100_00) // purchase
    }

    @Test
    fun `the facade gives the balance of an account instance, or of the account it finds by identifier`() {
        doubleEntry.balance(cash) shouldBe dollars(100_00)
        doubleEntry.balance(store, codes = listOf("layby"), code = "deposit") shouldBe dollars(200_00)
        doubleEntry.balance("cash", at = ago(7.days)) shouldBe dollars(100_00)
        doubleEntry.balance("bhd_wallet", from = now, to = fromNow(14.days)) shouldBe Money(200_00, bhd)
    }

    @Test
    fun `the facade's balance by identifier raises UnknownAccount for an account it cannot find`() {
        val scoped = shouldThrow<UnknownAccountException> { doubleEntry.balance("cash", scope = TestUser(1)) }
        val unknown = shouldThrow<UnknownAccountException> { doubleEntry.balance("nowhere") }

        scoped.message shouldBe "account: cash scoped?: true"
        unknown.message shouldBe "account: nowhere scoped?: false"
    }

    @Test
    fun `the account of a line read back gives its balance`() {
        val line = doubleEntry.lines.forAccount(cash).last()

        line.account.balance() shouldBe dollars(100_00)
    }

    @Test
    fun `an account instance made without a facade has no balance (X-10)`() {
        val configuration = DoubleEntryConfiguration().apply { accounts { define("cash") } }
        val unbound = configuration.accounts.account("cash")

        shouldThrow<IllegalStateException> { unbound.balance() }
    }

    private fun dollars(cents: Long): Money = Money(cents, usd)

    /** `<duration>.ago` of ActiveSupport, from the fixed now. */
    private fun ago(duration: Duration): Instant = today - duration

    /** `<duration>.from_now` of ActiveSupport, from the fixed now. */
    private fun fromNow(duration: Duration): Instant = today + duration

    /** `Timecop.freeze(time) { block }`: the clock stands at [time] while [block] runs, then is back at now. */
    private fun travel(
        time: Instant,
        block: () -> Unit,
    ) {
        now = time
        try {
            block()
        } finally {
            now = today
        }
    }
}
