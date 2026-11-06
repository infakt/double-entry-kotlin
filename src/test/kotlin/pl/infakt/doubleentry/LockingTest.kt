package pl.infakt.doubleentry

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.sql.SQLException
import java.util.Currency
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Clock
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.schema.AccountBalancesTable
import pl.infakt.doubleentry.schema.LinesTable
import pl.infakt.doubleentry.support.TestDatabase

/** locking_spec.rb: locking accounts on their balance records. */
class LockingTest {
    private val database: Database = TestDatabase.ledger(jsonMetadata = false)

    private val usd: Currency = Currency.getInstance("USD")

    // locking_spec.rb:16-39.
    private fun configure(fixtures: Boolean = false): DoubleEntry =
        DoubleEntry(database) {
            runningInsideTransactionalFixtures = fixtures
            val scope: (Any) -> Any? = { it }
            accounts {
                define("account_a", scopeIdentifier = scope)
                define("account_b", scopeIdentifier = scope)
                define("account_c", scopeIdentifier = scope)
                define("account_d", scopeIdentifier = scope)
                define("account_e")
            }
            transfers {
                define("account_a", "account_b", "test")
                define("account_c", "account_d", "test")
            }
        }

    private val doubleEntry = configure()

    private val accountA = doubleEntry.account("account_a", scope = "1")
    private val accountB = doubleEntry.account("account_b", scope = "2")
    private val accountD = doubleEntry.account("account_d", scope = "4")
    private val accountE = doubleEntry.account("account_e")

    private val balances = AccountBalances(database, doubleEntry.configuration)

    @Test
    fun `creates missing account balance records`() {
        val before = balanceCount()

        doubleEntry.lockAccounts(accountA) {}

        balanceCount() shouldBe before + 1
        val accountBalance = balances.findByAccount(accountA)
        accountBalance.shouldNotBeNull()
        accountBalance.balance shouldBe Money.zero(usd)
    }

    @Test
    fun `takes the balance for new account balance records from the lines table`() {
        seedLine(accountA, accountB, amount = 3_00, balance = 3_00)
        seedLine(accountA, accountB, amount = 7_00, balance = 10_00)
        val before = balanceCount()

        doubleEntry.lockAccounts(accountA) {}

        balanceCount() shouldBe before + 1
        val accountBalance = balances.findByAccount(accountA)
        accountBalance.shouldNotBeNull()
        accountBalance.balance shouldBe Money(10_00, usd)
    }

    @Test
    fun `prohibits locking inside a regular transaction`() {
        shouldThrow<LockMustBeOutermostTransactionException> {
            transaction(database) {
                maxAttempts = 1
                doubleEntry.lockAccounts(accountA, accountB) {}
            }
        }
    }

    @Test
    fun `prohibits a transfer inside a regular transaction`() {
        shouldThrow<LockMustBeOutermostTransactionException> {
            transaction(database) {
                maxAttempts = 1
                doubleEntry.transfer(Money(10_00, usd), from = accountA, to = accountB, code = "test")
            }
        }

        balanceCount() shouldBe 0
    }

    @Test
    fun `allows locking a scoped account and a non scoped account`() {
        shouldNotThrowAny {
            doubleEntry.lockAccounts(accountD, accountE) {}
        }
    }

    @Test
    fun `with the fixtures flag, locking inside a transaction joins it and creates the missing balance`() {
        val fixtures = configure(fixtures = true)
        val account = fixtures.account("account_a", scope = "1")
        var ran = false

        transaction(database) {
            maxAttempts = 1
            fixtures.lockAccounts(account) { ran = true }
        }

        ran shouldBe true
        balances.findByAccount(accountA).shouldNotBeNull()
    }

    @Test
    fun `the block runs inside the lock and its value is returned`() {
        doubleEntry.lockAccounts(accountA, accountB) { "done" } shouldBe "done"
    }

    @Test
    fun `the lock locks the accounts in their sort order and creates their balances in that order`() {
        doubleEntry.lockAccounts(accountD, accountE) {}

        val scopes =
            transaction(database) {
                AccountBalancesTable
                    .selectAll()
                    .orderBy(AccountBalancesTable.id)
                    .map { it[AccountBalancesTable.scope] }
            }
        scopes shouldBe listOf(null, "4")
    }

    @Test
    fun `an unscoped account takes its balance from the last line of its identifier`() {
        seedLine(accountE, accountA, amount = 5_00, balance = 5_00)

        doubleEntry.lockAccounts(accountE) {}

        balances.findByAccount(accountE)?.balance shouldBe Money(5_00, usd)
    }

    @Test
    fun `a block raising a driver error runs once, in one attempt`() {
        val runs = AtomicInteger()

        shouldThrow<SQLException> {
            doubleEntry.lockAccounts(accountA) {
                runs.incrementAndGet()
                TransactionManager.current().exec("SELECT 1 / 0")
            }
        }

        runs.get() shouldBe 1
    }

    @Test
    fun `a lock that waits longer than the lock timeout raises LockWaitTimeoutException (X-06)`() {
        doubleEntry.lockAccounts(accountA) {}
        val fixtures = configure(fixtures = true)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder =
            thread {
                doubleEntry.lockAccounts(accountA) {
                    locked.countDown()
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                }
            }

        try {
            locked.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
            val error =
                shouldThrow<LockWaitTimeoutException> {
                    transaction(database) {
                        maxAttempts = 1
                        exec("SET LOCAL lock_timeout = '100ms'")
                        fixtures.lockAccounts(fixtures.account("account_a", scope = "1")) {}
                    }
                }
            error.cause.shouldBeInstanceOf<SQLException>()
        } finally {
            release.countDown()
            holder.join()
        }
    }

    @Test
    fun `a deadlock while locking is not restarted, the driver's error goes through (X-03)`() {
        doubleEntry.lockAccounts(accountA, accountB) {}
        val fixtures = configure(fixtures = true)
        val first = fixtures.account("account_a", scope = "1")
        val second = fixtures.account("account_b", scope = "2")
        val barrier = CyclicBarrier(2)
        val secondLocks = AtomicInteger()
        val errors = mutableListOf<SQLException>()

        // Each thread holds one lock in its outer transaction and then asks for the other's: a deadlock.
        val threads =
            listOf(first to second, second to first).map { (held, wanted) ->
                thread {
                    try {
                        transaction(database) {
                            maxAttempts = 1
                            fixtures.lockAccounts(held) {}
                            barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                            fixtures.lockAccounts(wanted) { secondLocks.incrementAndGet() }
                        }
                    } catch (error: SQLException) {
                        synchronized(errors) { errors += error }
                    }
                }
            }
        threads.forEach { it.join() }

        errors.map { it.sqlState } shouldBe listOf(DEADLOCK_DETECTED)
        secondLocks.get() shouldBe 1
    }

    @Test
    fun `a deadlock while creating a missing balance record is not retried, the driver's error goes through (X-07)`() {
        val fixtures = configure(fixtures = true)
        val first = fixtures.account("account_a", scope = "1")
        val second = fixtures.account("account_b", scope = "2")
        balances.findByAccount(first).shouldBeNull()
        balances.findByAccount(second).shouldBeNull()
        val barrier = CyclicBarrier(2)
        val secondLocks = AtomicInteger()
        val errors = mutableListOf<SQLException>()

        // Each thread creates one balance record in its outer transaction, uncommitted, and then the other's: each
        // insert waits on the other's unique key, a deadlock on the insert.
        val threads =
            listOf(first to second, second to first).map { (created, wanted) ->
                thread {
                    try {
                        transaction(database) {
                            maxAttempts = 1
                            fixtures.lockAccounts(created) {}
                            barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                            fixtures.lockAccounts(wanted) { secondLocks.incrementAndGet() }
                        }
                    } catch (error: SQLException) {
                        synchronized(errors) { errors += error }
                    }
                }
            }
        threads.forEach { it.join() }

        errors.map { it.sqlState } shouldBe listOf(DEADLOCK_DETECTED)
        secondLocks.get() shouldBe 1
        balanceCount() shouldBe 2
    }

    private fun balanceCount(): Long = transaction(database) { AccountBalancesTable.selectAll().count() }

    /** `DoubleEntry::Line.create!` as locking_spec.rb:52-65 writes it, with code `test`. */
    private fun seedLine(
        account: AccountInstance,
        partner: AccountInstance,
        amount: Long,
        balance: Long,
    ) {
        val now = Clock.System.now()
        val lines = LinesTable.of(jsonMetadata = false)
        transaction(database) {
            lines.insert { row ->
                row[lines.account] = account.identifier
                row[lines.scope] = account.scopeIdentity
                row[lines.code] = "test"
                row[lines.amount] = amount
                row[lines.balance] = balance
                row[lines.partnerAccount] = partner.identifier
                row[lines.partnerScope] = partner.scopeIdentity
                row[lines.createdAt] = now
                row[lines.updatedAt] = now
            }
        }
    }

    private companion object {
        const val WAIT_SECONDS = 10L

        const val DEADLOCK_DETECTED = "40P01"
    }
}
