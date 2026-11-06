package pl.infakt.doubleentry

/**
 * The receiver of a [DoubleEntry.lockAccounts] block.
 *
 * [rollback] stands for the gem's `raise ActiveRecord::Rollback` inside `lock_accounts` (locking_spec.rb:125-132): it
 * leaves the block, the lock's transaction rolls back, and `lockAccounts` returns `null` (docs/deviations.md, X-11).
 */
class LockScope internal constructor() {
    /**
     * Leaves the block and rolls back the transaction of the lock that opened it, the outermost lock: inside a nested
     * lock it leaves the outer lock's block too. With [DoubleEntryConfiguration.runningInsideTransactionalFixtures], a
     * lock that joined a transaction already open leaves its block and rolls nothing back, as Active Record swallows a
     * `Rollback` in a joined transaction.
     */
    fun rollback(): Nothing = throw LockRollback()
}

/**
 * What [LockScope.rollback] throws to leave the block, without a stack trace; the lock that opened the transaction
 * catches it.
 */
internal class LockRollback : RuntimeException(null, null, false, false)
