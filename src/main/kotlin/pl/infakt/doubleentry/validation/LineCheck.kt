package pl.infakt.doubleentry.validation

import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import pl.infakt.doubleentry.AccountInstance
import pl.infakt.doubleentry.DoubleEntryConfiguration
import pl.infakt.doubleentry.Line
import pl.infakt.doubleentry.Lines
import pl.infakt.doubleentry.Locking
import pl.infakt.doubleentry.Money
import pl.infakt.doubleentry.schema.LineChecksTable

/**
 * A row of `double_entry_line_checks`: the gem's `DoubleEntry::Validation::LineCheck` record (validation/line_check.rb),
 * one run of the check. [lastLineId] is the id of the last line it checked, [errorsFound] whether it found a wrong
 * account, and [log] its report of each error, empty without one; the column is nullable, so a row written past the
 * library may have none.
 */
data class LineCheckRecord(
    val id: Long,
    val lastLineId: Long,
    val errorsFound: Boolean,
    val log: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * The line check of [database]: the gem's `DoubleEntry::Validation::LineCheck` (validation/line_check.rb), which audits
 * the lines written since the last check. Each new line's balance must be the previous line's balance plus its amount,
 * and each account with a new line must have a balance record equal to its running balance. Every error is reported in
 * the check's log, with the gem's texts.
 *
 * The lines are read by id, in batches of [batchSize], each batch after the last id of the one before (the gem's
 * `find_each`). Each call runs in transactions of [database] with one attempt; the balance record of each account is
 * compared inside a lock of the account, as `DoubleEntry.lock_accounts` takes it, so [perform] must run outside a
 * transaction of the caller unless [DoubleEntryConfiguration.runningInsideTransactionalFixtures] allows one.
 */
class LineCheck internal constructor(
    private val database: Database,
    private val configuration: DoubleEntryConfiguration,
    private val lines: Lines,
    private val locking: Locking,
    private val batchSize: Int = BATCH_SIZE,
) {
    /**
     * The `last_line_id` of the newest check by creation time, or 0 without a check: `LineCheck.last_line_id_checked`
     * (line_check.rb:8-10). Two checks created at the same time are told apart by id, the later one first; the gem
     * leaves their order to the database.
     */
    fun lastLineIdChecked(): Long =
        transaction(database) {
            maxAttempts = 1
            val table = LineChecksTable
            val newest =
                table
                    .select(table.lastLineId)
                    .orderBy(table.createdAt to SortOrder.DESC, table.id to SortOrder.DESC)
                    .limit(1)
                    .firstOrNull()
            newest?.get(table.lastLineId) ?: 0L
        }

    /**
     * Checks the lines of an id above [lastLineIdChecked] and the accounts of those lines, and returns the check it
     * writes, or `null` when there is no new line and nothing is written: `LineCheck#perform` (line_check.rb:16-41).
     *
     * - A line whose balance is not the balance of the previous line of its account and scope (by id, zero in the
     *   account's currency without one) plus its amount is an "Error on line" entry of the log, and its account is
     *   incorrect. The entry ends with the previous line, as a list of none or one, and the line, as Kotlin writes
     *   them, where the gem writes Active Record's `inspect` (docs/deviations.md, X-12).
     * - Then, for each account with a new line in the order first seen, inside a lock of the account: a balance record
     *   that differs from the account's running balance is an "Error on account" entry, and the account is incorrect.
     *
     * [fixer], when given, is then handed each incorrect account once, in the order found. The check is written last,
     * with the id of the last line checked, whether an account was incorrect, and the log.
     *
     * @throws pl.infakt.doubleentry.LockMustBeOutermostTransactionException when a transaction of [database] is
     *   already open.
     * @throws pl.infakt.doubleentry.LockWaitTimeoutException when the database gave up waiting for a lock.
     */
    fun perform(fixer: AccountRecalculator? = null): LineCheckRecord? {
        val log = StringBuilder()
        val activeAccounts = LinkedHashSet<AccountInstance>()
        val incorrectAccounts = LinkedHashSet<AccountInstance>()

        var lastLineId = lastLineIdChecked()
        do {
            val batch = lines.batchWithIdGreaterThan(lastLineId, batchSize)
            batch.forEach { line ->
                if (!runningBalanceCorrect(line, log)) incorrectAccounts += line.account
                activeAccounts += line.account
            }
            batch.lastOrNull()?.let { line -> lastLineId = line.id }
        } while (batch.size == batchSize)

        activeAccounts.forEach { account ->
            if (!cachedBalanceCorrect(account, log)) incorrectAccounts += account
        }

        if (fixer != null) incorrectAccounts.forEach(fixer::recalculateAccount)

        if (activeAccounts.isEmpty()) return null
        return create(lastLineId = lastLineId, errorsFound = incorrectAccounts.isNotEmpty(), log = log.toString())
    }

    /**
     * Writes a check, its timestamps the configuration's clock, and returns it: `LineCheck.create!`, as the gem's
     * specs make checks (line_check_spec.rb:11-17) and [perform] writes its own.
     */
    internal fun create(
        lastLineId: Long,
        errorsFound: Boolean,
        log: String?,
    ): LineCheckRecord =
        transaction(database) {
            maxAttempts = 1
            val now = configuration.clock.now()
            val table = LineChecksTable
            val id =
                table
                    .insertAndGetId { row ->
                        row[table.lastLineId] = lastLineId
                        row[table.errorsFound] = errorsFound
                        row[table.log] = log
                        row[table.createdAt] = now
                        row[table.updatedAt] = now
                    }.value
            LineCheckRecord(id, lastLineId, errorsFound, log, now, now)
        }

    /** The stored checks, by id. */
    internal fun records(): List<LineCheckRecord> =
        transaction(database) {
            maxAttempts = 1
            LineChecksTable
                .selectAll()
                .orderBy(LineChecksTable.id)
                .map(::record)
        }

    private fun record(row: ResultRow): LineCheckRecord {
        val table = LineChecksTable
        return LineCheckRecord(
            id = row[table.id].value,
            lastLineId = row[table.lastLineId],
            errorsFound = row[table.errorsFound],
            log = row[table.log],
            createdAt = row[table.createdAt],
            updatedAt = row[table.updatedAt],
        )
    }

    /** `running_balance_correct?` (line_check.rb:50-61): logs the error when [line]'s balance is wrong. */
    private fun runningBalanceCorrect(
        line: Line,
        log: StringBuilder,
    ): Boolean {
        val previousLine = lines.previousLine(line)
        val previousBalance = previousLine?.balance ?: Money.zero(line.account.currency)
        val correct = line.balance == line.amount + previousBalance
        if (!correct) log.append(lineErrorMessage(line, previousLine, previousBalance))
        return correct
    }

    /**
     * `line_error_message` (line_check.rb:97-106). The gem's last two lines are Active Record's `inspect` of the array
     * of the previous line and of the line; the port writes the list and the line as Kotlin does (X-12).
     */
    private fun lineErrorMessage(
        line: Line,
        previousLine: Line?,
        previousBalance: Money,
    ): String =
        "$SEPARATOR\n" +
            "Error on line #${line.id}: balance:${line.balance} != $previousBalance + ${line.amount}\n" +
            "$SEPARATOR\n" +
            "  ${listOfNotNull(previousLine)}\n" +
            "  $line\n" +
            "\n"

    /**
     * `cached_balance_correct?` (line_check.rb:108-120): inside a lock of [account], compares its balance record with
     * its running balance and logs the error when they differ.
     */
    private fun cachedBalanceCorrect(
        account: AccountInstance,
        log: StringBuilder,
    ): Boolean {
        val checked =
            locking.lockAccounts(listOf(account)) {
                val cachedBalance = locking.balanceForLockedAccount(account).balance
                val runningBalance = account.balance()
                val correct = cachedBalance == runningBalance
                if (!correct) {
                    log.append(
                        "$SEPARATOR\n" +
                            "Error on account $account: $cachedBalance (cached balance) != " +
                            "$runningBalance (running balance)\n" +
                            "$SEPARATOR\n" +
                            "\n",
                    )
                }
                correct
            }
        return checkNotNull(checked) { "the lock of $account was rolled back" }
    }

    private companion object {
        /** The batch size of the gem's `find_each`. */
        const val BATCH_SIZE = 1000

        /** The line around each entry of the log. */
        const val SEPARATOR = "*********************************"
    }
}
