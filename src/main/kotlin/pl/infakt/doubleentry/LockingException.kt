package pl.infakt.doubleentry

/**
 * The errors of locking accounts: the gem's four `DoubleEntry::Locking` errors (locking.rb:175-188). In the gem they
 * are plain `RuntimeError`s, no `DoubleEntryError`, so here they are no [DoubleEntryException]. Each subclass stands
 * for the gem class of the same name, with `Exception` added.
 *
 * An exception without a message stands for the gem's bare `fail`, whose message is the class name (oracle/unknowns.md,
 * U-03).
 */
sealed class LockingException(
    message: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** `LockMustBeOutermostTransaction`: accounts locked inside a transaction that is already open. */
class LockMustBeOutermostTransactionException(
    message: String? = null,
) : LockingException(message)

/** `LockNotHeld`: an account used inside a lock that does not lock it. */
class LockNotHeldException(
    message: String? = null,
) : LockingException(message)

/** `LockDisaster`: an account balance still missing after the lock created it. The gem says it should never happen. */
class LockDisasterException(
    message: String? = null,
) : LockingException(message)

/**
 * `LockWaitTimeout`: the database gave up waiting for a lock. The port raises it, as the gem does, for a driver error
 * whose message matches `/lock wait timeout/i` (locking.rb:45-51), and also for SQLState 55P03 (`lock_not_available`),
 * PostgreSQL's error of `lock_timeout` (docs/deviations.md, X-06). [cause] is the driver's error.
 */
class LockWaitTimeoutException(
    cause: Throwable? = null,
) : LockingException(null, cause)
