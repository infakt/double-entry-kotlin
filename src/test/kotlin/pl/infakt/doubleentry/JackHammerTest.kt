package pl.infakt.doubleentry

import io.kotest.matchers.collections.shouldBeEmpty
import java.util.Currency
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.random.Random
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.schema.AccountBalancesTable
import pl.infakt.doubleentry.schema.LinesTable
import pl.infakt.doubleentry.support.TestDatabase

/**
 * The gem's concurrency test, script/jack_hammer, in threads (its `--threads`): random transfers between five scoped
 * accounts from many threads at once, in rounds, the balance records deleted before each round so that the locks create
 * them again from the lines, and then the lines and the balance records reconciled (jack_hammer:161-214).
 *
 * The defaults are small enough for `./gradlew test`; the system properties `doubleentry.jackhammer.threads`,
 * `.transfers` (per thread and round), `.rounds` and `.seed` scale it up, such as `./gradlew test --tests
 * '*JackHammerTest' -Ddoubleentry.jackhammer.threads=20 -Ddoubleentry.jackhammer.transfers=1000`.
 */
class JackHammerTest {
    private val database: Database = TestDatabase.ledger(jsonMetadata = false)

    private val threadCount = property("threads")?.toInt() ?: DEFAULT_THREADS

    private val transfersPerThread = property("transfers")?.toInt() ?: DEFAULT_TRANSFERS

    private val roundCount = property("rounds")?.toInt() ?: DEFAULT_ROUNDS

    private val seed = property("seed")?.toLong() ?: DEFAULT_SEED

    private val usd: Currency = Currency.getInstance("USD")

    private val identifiers = (0 until ACCOUNT_COUNT).map { "account-$it" }

    // jack_hammer:84-111: the accounts, every transfer between them, and the accounts' instances for scope 1.
    private val doubleEntry =
        DoubleEntry(database) {
            val scope: (Any) -> Any? = { it }
            accounts { identifiers.forEach { define(it, scopeIdentifier = scope) } }
            transfers { identifiers.forEach { from -> identifiers.forEach { to -> define(from, to, "test") } } }
        }

    private val instances: List<AccountInstance> = identifiers.map { doubleEntry.account(it, scope = 1) }

    private val lines = LinesTable.of(jsonMetadata = false)

    @Test
    fun `random transfers from many threads at once reconcile with the lines and the balance records`() {
        // jack_hammer:114-142.
        repeat(roundCount) { round ->
            transaction(database) { AccountBalancesTable.deleteAll() }
            runRound(round)
        }

        reconcile().shouldBeEmpty()
    }

    /** One round of jack_hammer:124-131: [threadCount] threads, started together, each running its transfers. */
    private fun runRound(round: Int) {
        val start = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val workers =
            (0 until threadCount).map { index ->
                // Each thread has a random sequence of its own (jack_hammer:146), fixed by the seed.
                val random = Random(seed + round.toLong() * threadCount + index)
                thread {
                    try {
                        start.await()
                        repeat(transfersPerThread) { transferAtRandom(random) }
                    } catch (error: Throwable) {
                        errors += error
                    }
                }
            }
        start.countDown()
        workers.forEach { it.join() }
        errors.firstOrNull()?.let { throw it }
    }

    /** jack_hammer:151-155: a transfer of 1 to 1000 cents from one account to another. */
    private fun transferAtRandom(random: Random) {
        val from = instances.random(random)
        val to = (instances - from).random(random)
        val amount = random.nextLong(1, MAX_AMOUNT + 1)
        doubleEntry.transfer(Money(amount, usd), from = from, to = to, code = "test")
    }

    /**
     * jack_hammer:161-214: what failed to reconcile, empty when everything did. Every transfer wrote its two lines, and
     * for each account the running balance of its last line is the sum of its amounts and the balance of its record.
     * For an account that fails, each line whose running balance is not the previous one plus its amount is named.
     *
     * The script counts an account that fails to reconcile as an error only through its bad lines; the test counts
     * the account itself too, so a balance record off its lines fails it.
     */
    private fun reconcile(): List<String> {
        val failures = mutableListOf<String>()
        val expectedLines = 2L * threadCount * transfersPerThread * roundCount
        val lineCount = transaction(database) { lines.selectAll().count() }
        if (lineCount != expectedLines) failures += "Not enough Line records written: $lineCount of $expectedLines"

        instances.filterNot { reconciled(it) }.forEach { account ->
            failures += "Account ${account.identifier} failed to reconcile"
            linesOf(account).zipWithNext().forEach { (previous, next) ->
                if (previous.balance + next.amount != next.balance) failures += "Bad lines entry id = ${next.id}"
            }
        }
        return failures
    }

    /** jack_hammer:161-168; an account without lines reconciles when its record, if any, is zero. */
    private fun reconciled(account: AccountInstance): Boolean {
        val accountLines = linesOf(account)
        val cachedBalance = doubleEntry.accountBalances
            .findByAccount(account)
            ?.balance
            ?.minorUnits
        if (accountLines.isEmpty()) return cachedBalance == null || cachedBalance == 0L
        val finalBalance = accountLines.last().balance
        return finalBalance == accountLines.sumOf { it.amount } && finalBalance == cachedBalance
    }

    /** The lines of [account] in its scope, in the order of their ids (jack_hammer:162-163, 195-201). */
    private fun linesOf(account: AccountInstance): List<LineRow> {
        val scope = checkNotNull(account.scopeIdentity)
        return transaction(database) {
            lines
                .selectAll()
                .where { (lines.account eq account.identifier) and (lines.scope eq scope) }
                .orderBy(lines.id)
                .map { row -> LineRow(row[lines.id].value, row[lines.amount], row[lines.balance]) }
        }
    }

    private data class LineRow(
        val id: Long,
        val amount: Long,
        val balance: Long,
    )

    private companion object {
        /** jack_hammer:46, `--accounts`. */
        const val ACCOUNT_COUNT = 5

        const val DEFAULT_THREADS = 4

        const val DEFAULT_TRANSFERS = 25

        const val DEFAULT_ROUNDS = 3

        const val DEFAULT_SEED = 42L

        /** jack_hammer:153, `rand(1000) + 1`. */
        const val MAX_AMOUNT = 1000L

        fun property(name: String): String? = System.getProperty("doubleentry.jackhammer.$name")
    }
}
