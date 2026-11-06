package pl.infakt.doubleentry.parity

import java.sql.SQLException
import pl.infakt.doubleentry.AccountIdentifierTooLongException
import pl.infakt.doubleentry.AccountWouldBeSentNegativeException
import pl.infakt.doubleentry.AccountWouldBeSentPositiveException
import pl.infakt.doubleentry.DuplicateAccountException
import pl.infakt.doubleentry.DuplicateTransferException
import pl.infakt.doubleentry.LockDisasterException
import pl.infakt.doubleentry.LockMustBeOutermostTransactionException
import pl.infakt.doubleentry.LockNotHeldException
import pl.infakt.doubleentry.LockWaitTimeoutException
import pl.infakt.doubleentry.MismatchedCurrenciesException
import pl.infakt.doubleentry.MissingAccountException
import pl.infakt.doubleentry.ScopeIdentifierTooLongException
import pl.infakt.doubleentry.TransferCodeTooLongException
import pl.infakt.doubleentry.TransferIsNegativeException
import pl.infakt.doubleentry.TransferNotAllowedException
import pl.infakt.doubleentry.UnknownAccountException

/**
 * The gem class name each exception of the port maps to, as the oracle writes it in `{"class", "message"}`
 * (oracle/README.md, "Errors and the engine-message rule").
 */
object ErrorNames {
    /** The port's exceptions, by Kotlin class. A subclass not listed takes the name of its nearest listed superclass. */
    val byClass: Map<Class<out Throwable>, String> =
        mapOf(
            UnknownAccountException::class.java to "DoubleEntry::UnknownAccount",
            AccountIdentifierTooLongException::class.java to "DoubleEntry::AccountIdentifierTooLongError",
            ScopeIdentifierTooLongException::class.java to "DoubleEntry::ScopeIdentifierTooLongError",
            TransferNotAllowedException::class.java to "DoubleEntry::TransferNotAllowed",
            TransferIsNegativeException::class.java to "DoubleEntry::TransferIsNegative",
            TransferCodeTooLongException::class.java to "DoubleEntry::TransferCodeTooLongError",
            DuplicateAccountException::class.java to "DoubleEntry::DuplicateAccount",
            DuplicateTransferException::class.java to "DoubleEntry::DuplicateTransfer",
            AccountWouldBeSentNegativeException::class.java to "DoubleEntry::AccountWouldBeSentNegative",
            AccountWouldBeSentPositiveException::class.java to "DoubleEntry::AccountWouldBeSentPositiveError",
            MismatchedCurrenciesException::class.java to "DoubleEntry::MismatchedCurrencies",
            MissingAccountException::class.java to "DoubleEntry::MissingAccountError",
            LockMustBeOutermostTransactionException::class.java to
                "DoubleEntry::Locking::LockMustBeOutermostTransaction",
            LockNotHeldException::class.java to "DoubleEntry::Locking::LockNotHeld",
            LockDisasterException::class.java to "DoubleEntry::Locking::LockDisaster",
            LockWaitTimeoutException::class.java to "DoubleEntry::Locking::LockWaitTimeout",
        )

    /** Errors of the database engine or its driver, by SQLState. Their messages are recorded as `<engine>`. */
    val bySqlState: Map<String, String> =
        mapOf(
            "23502" to "ActiveRecord::NotNullViolation",
            "23505" to "ActiveRecord::RecordNotUnique",
        )

    /** Any other driver error: the gem's Active Record wraps it in `StatementInvalid`. */
    const val STATEMENT_INVALID = "ActiveRecord::StatementInvalid"

    /**
     * Ruby's `RuntimeError`, what a bare `raise 'message'` raises, such as a scope identifier of a scenario
     * (oracle/README.md, "Ops"). Only a plain [RuntimeException] maps to it, not its subclasses.
     */
    const val RUNTIME_ERROR = "RuntimeError"

    /** The gem class name of [error], or its Kotlin class name when nothing maps it, so a mismatch shows what it is. */
    fun gemName(error: Throwable): String {
        generateSequence<Class<*>>(error.javaClass) { it.superclass }
            .firstNotNullOfOrNull { byClass[it] }
            ?.let { return it }
        sqlException(error)?.let { return bySqlState[it.sqlState] ?: STATEMENT_INVALID }
        if (error.javaClass == RuntimeException::class.java) return RUNTIME_ERROR
        return error.javaClass.name
    }

    private fun sqlException(error: Throwable): SQLException? =
        generateSequence(error) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
}
