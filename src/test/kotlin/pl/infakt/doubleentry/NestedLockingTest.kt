package pl.infakt.doubleentry

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.sql.SQLException
import java.util.Currency
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.schema.LinesTable
import pl.infakt.doubleentry.support.TestDatabase

/**
 * locking_spec.rb:93-178: transfers and nested locks inside a lock, rollbacks, and how a lock handles a driver error;
 * and the transactional-fixtures flag (locking.rb:116). The concurrent examples (locking_spec.rb:180-213) belong to the
 * concurrency stage.
 */
class NestedLockingTest {
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
    private val accountC = doubleEntry.account("account_c", scope = "3")
    private val accountD = doubleEntry.account("account_d", scope = "4")
    private val accountE = doubleEntry.account("account_e")

    private val balances = AccountBalances(database, doubleEntry.configuration)

    @Test
    fun `allows a transfer inside a lock if we've locked the transaction accounts`() {
        shouldNotThrowAny {
            doubleEntry.lockAccounts(accountA, accountB) {
                doubleEntry.transfer(Money(10_00, usd), from = accountA, to = accountB, code = "test")
            }
        }
    }

    @Test
    fun `does not allow a transfer inside a lock if the right locks aren't held`() {
        shouldThrowWithMessage<LockNotHeldException>("No lock held for account: account_b, scope 2") {
            doubleEntry.lockAccounts(accountA, accountC) {
                doubleEntry.transfer(Money(10_00, usd), from = accountA, to = accountB, code = "test")
            }
        }
    }

    @Test
    fun `allows nested locks if the outer lock locks all the accounts`() {
        shouldNotThrowAny {
            doubleEntry.lockAccounts(accountA, accountB) {
                doubleEntry.lockAccounts(accountA, accountB) {}
            }
        }
    }

    @Test
    fun `prohibits nested locks if the out lock doesn't lock all the accounts`() {
        shouldThrowWithMessage<LockNotHeldException>("No lock held for account: account_b, scope 2") {
            doubleEntry.lockAccounts(accountA) {
                doubleEntry.lockAccounts(accountA, accountB) {}
            }
        }
    }

    @Test
    fun `rolls back a locking transaction`() {
        doubleEntry.lockAccounts(accountA, accountB) {
            doubleEntry.transfer(Money(10_00, usd), from = accountA, to = accountB, code = "test")
            rollback()
        }

        doubleEntry.balance(accountA) shouldBe Money.zero(usd)
        doubleEntry.balance(accountB) shouldBe Money.zero(usd)
    }

    @Test
    fun `rolls back a locking transaction if there's an exception`() {
        shouldThrowWithMessage<RuntimeException>("Yeah, right") {
            doubleEntry.lockAccounts(accountA, accountB) {
                doubleEntry.transfer(Money(10_00, usd), from = accountA, to = accountB, code = "test")
                throw RuntimeException("Yeah, right")
            }
        }

        doubleEntry.balance(accountA) shouldBe Money.zero(usd)
        doubleEntry.balance(accountB) shouldBe Money.zero(usd)
    }

    // locking_spec.rb:155 stubs with_restart_on_deadlock to raise; the seam raises where that method runs.
    @Test
    fun `handling StatementInvalid errors, non lock wait timeout errors, re-raises the StatementInvalid error`() {
        val error = SQLException("some other error")
        doubleEntry.locking.beforeGrabLocks = { throw error }

        val thrown = shouldThrow<SQLException> { doubleEntry.lockAccounts(accountD, accountE) {} }

        thrown shouldBeSameInstanceAs error
    }

    // locking_spec.rb:168 stubs with_restart_on_deadlock to raise; the seam raises where that method runs.
    @Test
    fun `handling StatementInvalid errors, lock wait timeout errors, raises a LockWaitTimeout error`() {
        val error = SQLException("lock wait timeout")
        doubleEntry.locking.beforeGrabLocks = { throw error }

        val thrown = shouldThrow<LockWaitTimeoutException> { doubleEntry.lockAccounts(accountD, accountE) {} }

        thrown.cause shouldBeSameInstanceAs error
    }

    @Test
    fun `the gem's lock wait timeout test ignores case and finds the words inside a longer message`() {
        val error = SQLException("Lock wait timeout exceeded; try restarting transaction", "HY000")
        doubleEntry.locking.beforeGrabLocks = { throw error }

        val thrown = shouldThrow<LockWaitTimeoutException> { doubleEntry.lockAccounts(accountD, accountE) {} }

        thrown.cause shouldBeSameInstanceAs error
    }

    @Test
    fun `an error of the block that is no driver error goes through, whatever its message`() {
        shouldThrowWithMessage<IllegalStateException>("lock wait timeout") {
            doubleEntry.lockAccounts(accountD, accountE) { throw IllegalStateException("lock wait timeout") }
        }
    }

    @Test
    fun `a transfer that waits longer than the lock timeout raises LockWaitTimeoutException (X-06)`() {
        doubleEntry.lockAccounts(accountA, accountB) {}
        val fixtures = configure(fixtures = true)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder =
            thread {
                doubleEntry.lockAccounts(accountB) {
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
                        fixtures.transfer(
                            Money(10_00, usd),
                            from = fixtures.account("account_a", scope = "1"),
                            to = fixtures.account("account_b", scope = "2"),
                            code = "test",
                        )
                    }
                }
            sqlStates(error) shouldContain LOCK_NOT_AVAILABLE
        } finally {
            release.countDown()
            holder.join()
        }

        doubleEntry.balance(accountA) shouldBe Money.zero(usd)
        lineCount() shouldBe 0
    }

    @Test
    fun `rollback leaves the block, rolls the lock's transaction back and the lock returns null (X-11)`() {
        val rollBack = true
        var ranOn = false

        val value =
            doubleEntry.lockAccounts(accountA, accountB) {
                doubleEntry.transfer(Money(10_00, usd), from = accountA, to = accountB, code = "test")
                if (rollBack) rollback()
                ranOn = true
                "done"
            }

        value.shouldBeNull()
        ranOn shouldBe false
        lineCount() shouldBe 0
        balances.findByAccount(accountA)?.balance shouldBe Money.zero(usd)
        balances.findByAccount(accountB)?.balance shouldBe Money.zero(usd)
    }

    @Test
    fun `a rollback inside a nested lock leaves the outer lock too and rolls its transaction back`() {
        var ranOn = false

        val value =
            doubleEntry.lockAccounts(accountA, accountB) {
                doubleEntry.transfer(Money(10_00, usd), from = accountA, to = accountB, code = "test")
                doubleEntry.lockAccounts(accountA) { rollback() }
                ranOn = true
            }

        value.shouldBeNull()
        ranOn shouldBe false
        lineCount() shouldBe 0
        doubleEntry.balance(accountA) shouldBe Money.zero(usd)
    }

    @Test
    fun `with the fixtures flag, a lock of two accounts inside a regular transaction goes through`() {
        val fixtures = configure(fixtures = true)
        val first = fixtures.account("account_a", scope = "1")
        val second = fixtures.account("account_b", scope = "2")
        var ran = false

        transaction(database) {
            maxAttempts = 1
            fixtures.lockAccounts(first, second) { ran = true }
        }

        ran shouldBe true
        balances.findByAccount(accountA).shouldNotBeNull()
        balances.findByAccount(accountB).shouldNotBeNull()
    }

    @Test
    fun `with the fixtures flag, a rollback in a lock that joined a transaction rolls nothing back`() {
        val fixtures = configure(fixtures = true)
        val first = fixtures.account("account_a", scope = "1")
        val second = fixtures.account("account_b", scope = "2")
        var value: String? = "not run"

        transaction(database) {
            maxAttempts = 1
            value =
                fixtures.lockAccounts(first, second) {
                    fixtures.transfer(Money(10_00, usd), from = first, to = second, code = "test")
                    // The lock's rollback, not the transaction's: LockScope is the nearer receiver.
                    rollback()
                }
        }

        value.shouldBeNull()
        doubleEntry.balance(accountA) shouldBe Money(-10_00, usd)
        doubleEntry.balance(accountB) shouldBe Money(10_00, usd)
    }

    @Test
    fun `two transfers inside one lock each read the balance the other left`() {
        doubleEntry.lockAccounts(accountA, accountB) {
            doubleEntry.transfer(Money(10_00, usd), from = accountA, to = accountB, code = "test")
            doubleEntry.transfer(Money(5_00, usd), from = accountA, to = accountB, code = "test")
        }

        balances.findByAccount(accountA)?.balance shouldBe Money(-15_00, usd)
        balances.findByAccount(accountB)?.balance shouldBe Money(15_00, usd)
        doubleEntry.balance(accountA) shouldBe Money(-15_00, usd)
    }

    @Test
    fun `the balance of a locked account inside the lock is its record`() {
        doubleEntry.lockAccounts(accountA, accountB) {
            doubleEntry.transfer(Money(10_00, usd), from = accountA, to = accountB, code = "test")
            doubleEntry.locking.balanceForLockedAccount(accountB).balance shouldBe Money(10_00, usd)
        }
    }

    @Test
    fun `the balance of an account outside a lock raises LockNotHeld`() {
        shouldThrowWithMessage<LockNotHeldException>("No lock held for account: account_a, scope 1") {
            doubleEntry.locking.balanceForLockedAccount(accountA)
        }
    }

    @Test
    fun `the balance of an account the lock does not hold raises LockNotHeld`() {
        shouldThrowWithMessage<LockNotHeldException>("No lock held for account: account_b, scope 2") {
            doubleEntry.lockAccounts(accountA) { doubleEntry.locking.balanceForLockedAccount(accountB) }
        }
    }

    private fun lineCount(): Long = transaction(database) { LinesTable.of(jsonMetadata = false).selectAll().count() }

    /** The SQLStates of the driver errors among [error]'s causes. */
    private fun sqlStates(error: Throwable): List<String?> =
        generateSequence(error.cause) { it.cause }.filterIsInstance<SQLException>().map { it.sqlState }.toList()

    private companion object {
        const val WAIT_SECONDS = 10L

        const val LOCK_NOT_AVAILABLE = "55P03"
    }
}
