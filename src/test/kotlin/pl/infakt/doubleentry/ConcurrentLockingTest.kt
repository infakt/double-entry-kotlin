package pl.infakt.doubleentry

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.shouldBe
import java.util.Currency
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.schema.AccountBalancesTable
import pl.infakt.doubleentry.schema.LinesTable
import pl.infakt.doubleentry.support.TestDatabase

/**
 * locking_spec.rb:180-213, `concurrent locking`: locks of other accounts taken from two threads at the same time. The
 * gem skips it on SQLite; the port runs it on PostgreSQL. Where the spec sleeps so that the locks overlap, the port
 * waits on latches, so the locks overlap on every run and a lock that blocked the other thread would fail the test.
 */
class ConcurrentLockingTest {
    private val database: Database = TestDatabase.ledger(jsonMetadata = false)

    private val usd: Currency = Currency.getInstance("USD")

    // locking_spec.rb:16-39.
    private val doubleEntry =
        DoubleEntry(database) {
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

    private val accountA = doubleEntry.account("account_a", scope = "1")
    private val accountB = doubleEntry.account("account_b", scope = "2")
    private val accountC = doubleEntry.account("account_c", scope = "3")
    private val accountD = doubleEntry.account("account_d", scope = "4")

    @Test
    fun `allows multiple threads to lock at the same time`() {
        val holding = CountDownLatch(1)
        val firstDone = CountDownLatch(1)
        var firstDoneWhileHolding = false

        shouldNotThrowAny {
            inThreads(
                {
                    // The spec's `sleep 0.05`: the other thread holds its lock first.
                    holding.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
                    doubleEntry.lockAccounts(accountA, accountB) {
                        doubleEntry.transfer(Money(10_00, usd), from = accountA, to = accountB, code = "test")
                    }
                    firstDone.countDown()
                },
                {
                    doubleEntry.lockAccounts(accountC, accountD) {
                        holding.countDown()
                        // The spec's `sleep 0.1`, until the other thread's transfer is done.
                        firstDoneWhileHolding = firstDone.await(WAIT_SECONDS, TimeUnit.SECONDS)
                        doubleEntry.transfer(Money(10_00, usd), from = accountC, to = accountD, code = "test")
                    }
                },
            )
        }

        firstDoneWhileHolding shouldBe true
        lineCount() shouldBe 4
    }

    @Test
    fun `allows multiple threads to lock accounts without balances at the same time`() {
        balanceCount() shouldBe 0
        // The spec's `sleep 0.1` inside each lock: both threads are inside their locks at the same time.
        val bothLocked = CyclicBarrier(2)

        shouldNotThrowAny {
            inThreads(
                { doubleEntry.lockAccounts(accountA, accountB) { bothLocked.await(WAIT_SECONDS, TimeUnit.SECONDS) } },
                { doubleEntry.lockAccounts(accountC, accountD) { bothLocked.await(WAIT_SECONDS, TimeUnit.SECONDS) } },
            )
        }

        balanceCount() shouldBe 4
    }

    /** Runs each of [blocks] in a thread of its own, joins them all and throws the first error, as Ruby's `join`. */
    private fun inThreads(vararg blocks: () -> Unit) {
        val errors = ConcurrentLinkedQueue<Throwable>()
        blocks
            .map { block ->
                thread {
                    try {
                        block()
                    } catch (error: Throwable) {
                        errors += error
                    }
                }
            }.forEach { it.join() }
        errors.firstOrNull()?.let { throw it }
    }

    private fun balanceCount(): Long = transaction(database) { AccountBalancesTable.selectAll().count() }

    private fun lineCount(): Long = transaction(database) { LinesTable.of(jsonMetadata = false).selectAll().count() }

    private companion object {
        const val WAIT_SECONDS = 10L
    }
}
