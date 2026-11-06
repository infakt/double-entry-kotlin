package pl.infakt.doubleentry

import java.sql.SQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * Locks accounts on [database]: the gem's `DoubleEntry::Locking` (locking.rb). The lock is on the accounts' rows of
 * `double_entry_account_balances`, taken `FOR UPDATE` in one transaction, and a row missing for an account is created
 * first, its balance from the account's lines.
 *
 * The locks held are kept per thread, as the gem keeps them in `Thread.current[:double_entry_locks]`.
 *
 * A deadlock while the locks are grabbed restarts the lock's transaction, and one while a missing balance record is
 * inserted runs the insert again, as the gem's locking extensions do ([LockingExtensions]); each is told to
 * [DoubleEntryConfiguration.events]. Inside a transaction of the caller (allowed by
 * [DoubleEntryConfiguration.runningInsideTransactionalFixtures]) neither runs again, as PostgreSQL has aborted that
 * transaction: the driver's error goes through (docs/deviations.md, X-03 and X-07). Exposed runs every transaction in
 * one attempt.
 */
internal class Locking(
    private val database: Database,
    private val configuration: DoubleEntryConfiguration,
) {
    private val balances = AccountBalances(database, configuration)

    private val calculator = BalanceCalculator(database, configuration)

    private val extensions = LockingExtensions(configuration)

    /**
     * A seam for the tests and the parity harness, a no-op otherwise: it runs in the lock's transaction before the
     * balance records are read, inside the gem's `with_restart_on_deadlock` around `grab_locks` (locking.rb:130), so
     * an error it throws is handled as an error of the database there would be: a deadlock restarts the transaction,
     * any other error leaves [lockAccounts]. locking_spec.rb:155 and 168 stub that method to raise.
     */
    internal var beforeGrabLocks: () -> Unit = {}

    /**
     * A seam for the tests, a no-op otherwise: it runs before each missing balance record of an account is inserted,
     * inside the gem's `create_ignoring_duplicates!` (locking.rb:162-170), so an error it throws is handled as an
     * error of that insert would be: a deadlock inserts again, a duplicate is ignored.
     */
    internal var beforeInsertBalance: (AccountInstance) -> Unit = {}

    /**
     * Runs [block] with [accounts] locked and returns its value: `Locking.lock_accounts` (locking.rb:35-51). Inside a
     * lock it checks that the lock holds every account and runs [block] in it; otherwise it opens the lock's
     * transaction. When [block] calls [LockScope.rollback], the transaction of the outermost lock rolls back and that
     * lock returns `null` (docs/deviations.md, X-11).
     *
     * @throws LockMustBeOutermostTransactionException when a transaction of [database] is already open, beyond the one
     *   [DoubleEntryConfiguration.runningInsideTransactionalFixtures] allows.
     * @throws LockNotHeldException inside a lock that does not lock all of [accounts].
     * @throws LockDisasterException when a balance record is still missing after it was created.
     * @throws LockWaitTimeoutException for a driver error whose message matches the gem's `/lock wait timeout/i`, or
     *   when the database gave up waiting for a lock (SQLState 55P03), where the gem on PostgreSQL re-raises the
     *   driver's error (docs/deviations.md, X-06). Any other error is thrown as it is.
     */
    fun <T> lockAccounts(
        accounts: List<AccountInstance>,
        block: LockScope.() -> T,
    ): T? {
        // Always lock in the same order, to avoid deadlocks.
        val lock = Lock(accounts.sorted())
        try {
            return if (lock.inALockedTransaction()) {
                lock.ensureLocked()
                LockScope().block()
            } else {
                lock.performLock(block)
            }
        } catch (error: Exception) {
            if (error !is LockingException && isLockWaitTimeout(error)) {
                throw LockWaitTimeoutException(error)
            }
            throw error
        }
    }

    /**
     * The balance record of [account], which the thread's lock must hold: `Locking.balance_for_locked_account`
     * (locking.rb:55-57, 89-93). Where the gem returns the record the lock read, the port reads it again in the lock's
     * transaction: an [AccountBalance] does not change when its row is updated, so the record the lock read would miss
     * an earlier transfer inside the same lock.
     *
     * @throws LockNotHeldException outside a lock, or inside one that does not lock [account].
     */
    fun balanceForLockedAccount(account: AccountInstance): AccountBalance {
        Lock(listOf(account)).ensureLocked()
        return balances.findByAccount(account, lock = true) ?: throw LockDisasterException()
    }

    /** The gem's `Locking::Lock` (locking.rb:59-171) on [accounts], sorted. */
    private inner class Lock(
        private val accounts: List<AccountInstance>,
    ) {
        private var accountsWithoutBalances: List<AccountInstance> = emptyList()

        /** Whether the lock runs in transactions of its own, outside one of the caller ([performLock]). */
        private var outermost = true

        /** Whether the thread is inside a lock (locking.rb:76-78). */
        fun inALockedTransaction(): Boolean = locks.get() != null

        /** Throws [LockNotHeldException] unless the thread's lock holds every account (locking.rb:80-86). */
        fun ensureLocked() {
            val held: Map<AccountInstance, AccountBalance>? = locks.get()
            accounts.forEach { account ->
                if (held == null || account !in held) {
                    // Ruby interpolates a nil scope as an empty string.
                    throw LockNotHeldException(
                        "No lock held for account: ${account.identifier}, scope ${account.scope ?: ""}",
                    )
                }
            }
        }

        /**
         * Locks the accounts, creating their balance records if needed, and runs [block] (locking.rb:66-73). A
         * [LockScope.rollback] in [block] leaves the lock's transaction, which rolls back, and the lock returns `null`,
         * as Active Record's `transaction` swallows a `Rollback`.
         */
        fun <T> performLock(block: LockScope.() -> T): T? {
            ensureOutermostTransaction()
            outermost = openTransactions() == 0
            try {
                lockAndCall(block)?.let { return it.value }
                createMissingAccountBalances()
                val called = lockAndCall(block) ?: throw LockDisasterException()
                return called.value
            } catch (rollback: LockRollback) {
                return null
            }
        }

        /**
         * Throws [LockMustBeOutermostTransactionException] when more transactions of [database] are open on the thread
         * than allowed: none, or one with the fixtures flag (locking.rb:114-120). Exposed joins a transaction opened
         * inside another unless the database uses nested transactions, so those are counted through their outer ones.
         */
        private fun ensureOutermostTransaction() {
            val allowed = if (configuration.runningInsideTransactionalFixtures) 1 else 0
            if (openTransactions() > allowed) throw LockMustBeOutermostTransactionException()
        }

        /** The transactions of [database] open on the thread, each counted through its outer ones. */
        private fun openTransactions(): Int {
            val current = TransactionManager.currentOrNull()?.takeIf { it.db == database }
            return generateSequence(current) { it.outerTransaction }.count()
        }

        /**
         * In a restartable transaction, locks the balance record of each account and runs [block] with the locks held
         * (locking.rb:126-160). When a record is missing, it keeps the accounts without one, does not run [block] and
         * returns `null`.
         */
        private fun <T> lockAndCall(block: LockScope.() -> T): Called<T>? =
            extensions.restartableTransaction(database) {
                val found = grabLocks()
                if (found.any { it == null }) {
                    accountsWithoutBalances = accounts.filterIndexed { index, _ -> found[index] == null }
                    null
                } else {
                    locks.set(accounts.zip(found.filterNotNull()).toMap())
                    try {
                        Called(LockScope().block())
                    } finally {
                        locks.remove()
                    }
                }
            }

        /**
         * The balance record of each account, read `FOR UPDATE`, or `null` for a missing one: `grab_locks`
         * (locking.rb:141-160) inside `with_restart_on_deadlock` (locking.rb:130), so that a deadlock restarts the
         * lock's transaction. Inside a transaction of the caller a deadlock goes through (docs/deviations.md, X-03).
         */
        private fun grabLocks(): List<AccountBalance?> {
            val grab = {
                beforeGrabLocks()
                accounts.map { balances.findByAccount(it, lock = true) }
            }
            return if (outermost) extensions.withRestartOnDeadlock(grab) else grab()
        }

        /**
         * Creates the missing balance records, each with the balance of the account's lines (`account.balance`, the
         * running balance of its last line, BalanceCalculator), ignoring a record another transaction created
         * meanwhile and inserting again after a deadlock: `create_missing_account_balances` (locking.rb:162-170) and
         * `create_ignoring_duplicates!` (locking_extensions.rb:28-36). Inside a transaction of the caller a deadlock
         * goes through (docs/deviations.md, X-07).
         */
        private fun createMissingAccountBalances() {
            accountsWithoutBalances.forEach { account ->
                val balance = calculator.calculate(account)
                val insert = {
                    beforeInsertBalance(account)
                    balances.insert(account, balance)
                }
                if (outermost) extensions.createIgnoringDuplicates(insert) else extensions.ignoringDuplicates(insert)
            }
        }
    }

    /** The value of a block that ran, which may itself be `null`. */
    private class Called<T>(
        val value: T,
    )

    private companion object {
        /** The thread's locks: each locked account's balance record, or `null` outside a lock. */
        val locks = ThreadLocal<Map<AccountInstance, AccountBalance>>()

        const val LOCK_NOT_AVAILABLE = "55P03"

        /** The gem's test of a lock wait timeout on the message of a driver error (locking.rb:46). */
        val lockWaitTimeoutMessage = Regex("lock wait timeout", RegexOption.IGNORE_CASE)

        /**
         * Whether [error] or one of its causes is a driver error of a lock wait timeout: its message matches
         * [lockWaitTimeoutMessage], as the gem tests an `ActiveRecord::StatementInvalid`, or its SQLState is 55P03
         * (docs/deviations.md, X-06).
         */
        fun isLockWaitTimeout(error: Throwable): Boolean =
            generateSequence(error) { it.cause }
                .filterIsInstance<SQLException>()
                .any { driverError ->
                    driverError.sqlState == LOCK_NOT_AVAILABLE ||
                        lockWaitTimeoutMessage.containsMatchIn(driverError.message.orEmpty())
                }
    }
}
