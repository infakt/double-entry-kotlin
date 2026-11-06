package pl.infakt.doubleentry

import java.sql.SQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * The gem's `ActiveRecord::LockingExtensions` (locking_extensions.rb): a transaction run again when it asks to, a
 * deadlock turned into that request, and an insert that ignores a duplicate and runs again after a deadlock. Each
 * handled error is told to [DoubleEntryConfiguration.events] of [configuration].
 *
 * The gem tests an `ActiveRecord::StatementInvalid` by its message; the port tests each [SQLException] of the error
 * and its causes, by the gem's regex on the message or by PostgreSQL's SQLState for the same error.
 */
internal class LockingExtensions(
    private val configuration: DoubleEntryConfiguration,
) {
    /**
     * Runs [block] in a transaction of [database], in one attempt, and runs the transaction again from the start as
     * long as it throws [RestartTransaction]: `restartable_transaction` (locking_extensions.rb:7-13). Inside a
     * transaction already open on [database], Exposed joins it, so only [block] runs again.
     */
    fun <T> restartableTransaction(
        database: Database,
        block: JdbcTransaction.() -> T,
    ): T {
        while (true) {
            try {
                return transaction(database) {
                    maxAttempts = 1
                    block()
                }
            } catch (restart: RestartTransaction) {
                // The gem's `retry`: the transaction rolled back, run it again.
            }
        }
    }

    /**
     * Runs [block]; a deadlock it throws is told as [DoubleEntryEvents.deadlockRestart] and becomes
     * [RestartTransaction], any other error goes through: `with_restart_on_deadlock` (locking_extensions.rb:15-26).
     */
    fun <T> withRestartOnDeadlock(block: () -> T): T =
        try {
            block()
        } catch (error: Exception) {
            if (!isDeadlock(error)) throw error
            configuration.events.deadlockRestart(error)
            throw RestartTransaction()
        }

    /**
     * Runs [create], ignoring a duplicate ([ignoringDuplicates]) and running it again after each deadlock, which is
     * told as [DoubleEntryEvents.deadlockRetry]: `create_ignoring_duplicates!` and `retry_deadlocks`
     * (locking_extensions.rb:28-36, 57-72). The value is [create]'s, or `null` for an ignored duplicate.
     */
    fun <T> createIgnoringDuplicates(create: () -> T): T? {
        while (true) {
            try {
                return ignoringDuplicates(create)
            } catch (error: Exception) {
                if (!isDeadlock(error)) throw error
                // Somebody else is in the midst of creating the record: run it again, so they are done first.
                configuration.events.deadlockRetry(error)
            }
        }
    }

    /**
     * Runs [create]; a duplicate it throws is told as [DoubleEntryEvents.duplicateIgnored] and ignored, as someone
     * else has created the record already, and the value is `null`; any other error goes through: `ignoring_duplicates`
     * (locking_extensions.rb:40-55).
     */
    fun <T> ignoringDuplicates(create: () -> T): T? =
        try {
            create()
        } catch (error: Exception) {
            if (!isDuplicate(error)) throw error
            configuration.events.duplicateIgnored(error)
            null
        }

    private companion object {
        /** PostgreSQL's `deadlock_detected`. */
        const val DEADLOCK_DETECTED = "40P01"

        /** PostgreSQL's `unique_violation`. */
        const val UNIQUE_VIOLATION = "23505"

        /** The gem's deadlock messages: `/deadlock/i` and `/database is locked/i` (locking_extensions.rb:20, 63). */
        val deadlockMessages =
            listOf(
                Regex("deadlock", RegexOption.IGNORE_CASE),
                Regex("database is locked", RegexOption.IGNORE_CASE),
            )

        /** The gem's duplicate messages: `/duplicate/i` and `/ConstraintException/` (locking_extensions.rb:48). */
        val duplicateMessages = listOf(Regex("duplicate", RegexOption.IGNORE_CASE), Regex("ConstraintException"))

        fun isDeadlock(error: Throwable): Boolean = driverErrorMatches(error, DEADLOCK_DETECTED, deadlockMessages)

        fun isDuplicate(error: Throwable): Boolean = driverErrorMatches(error, UNIQUE_VIOLATION, duplicateMessages)

        /** Whether [error] or one of its causes is a driver error of [sqlState] or with a message one of [messages]. */
        fun driverErrorMatches(
            error: Throwable,
            sqlState: String,
            messages: List<Regex>,
        ): Boolean =
            generateSequence(error) { it.cause }
                .filterIsInstance<SQLException>()
                .any { driverError ->
                    driverError.sqlState == sqlState ||
                        messages.any { it.containsMatchIn(driverError.message.orEmpty()) }
                }
    }
}

/**
 * Thrown inside [LockingExtensions.restartableTransaction] to run its transaction again from the start: the gem's
 * `ActiveRecord::RestartTransaction` (locking_extensions.rb:75-77). It has no cause, so a deadlock that asked for it
 * is not matched again by an extension around the transaction.
 */
internal class RestartTransaction : RuntimeException()
