package pl.infakt.doubleentry

import java.lang.System.Logger.Level

/**
 * What locking accounts tells about the database errors it handled: the gem's `ActiveSupport::Notifications` of
 * `lib/active_record/locking_extensions.rb`, one function per event, each with the driver's error. Set an
 * implementation on [DoubleEntryConfiguration.events]; a function not overridden logs as the gem's `LogSubscriber`
 * does (locking_extensions/log_subscriber.rb), through the [System.Logger] named after this interface.
 */
interface DoubleEntryEvents {
    /**
     * A deadlock while the locks were grabbed: the lock's transaction runs again from the start. The gem's
     * `deadlock_restart.double_entry` (locking_extensions.rb:16-26), logged as "Deadlock causing restart".
     */
    fun deadlockRestart(exception: Throwable) {
        LogSubscriber.log("Deadlock causing restart", exception)
    }

    /**
     * A deadlock while a missing balance record was inserted: the insert runs again. The gem's
     * `deadlock_retry.double_entry` (locking_extensions.rb:57-72), logged as "Deadlock causing retry".
     */
    fun deadlockRetry(exception: Throwable) {
        LogSubscriber.log("Deadlock causing retry", exception)
    }

    /**
     * A balance record another transaction inserted first: the duplicate is ignored. The gem's
     * `duplicate_ignore.double_entry` (locking_extensions.rb:40-55), logged as "Duplicate ignored".
     */
    fun duplicateIgnored(exception: Throwable) {
        LogSubscriber.log("Duplicate ignored", exception)
    }

    /** The default: every event logged, as the gem's `LogSubscriber` attached to `double_entry` does. */
    companion object Logging : DoubleEntryEvents
}

/** The gem's `LogSubscriber` (log_subscriber.rb): the event at `info`, then its exception at `debug`. */
private object LogSubscriber {
    private val logger: System.Logger = System.getLogger(DoubleEntryEvents::class.java.name)

    fun log(
        message: String,
        exception: Throwable,
    ) {
        logger.log(Level.INFO, message)
        logger.log(Level.DEBUG, exception.toString(), exception)
    }
}
