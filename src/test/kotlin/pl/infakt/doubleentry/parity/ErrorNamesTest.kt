package pl.infakt.doubleentry.parity

import io.kotest.matchers.shouldBe
import java.sql.SQLException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
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

class ErrorNamesTest {
    @Test
    fun `a driver error is named by its SQLState`() {
        ErrorNames.gemName(SQLException("ERROR: duplicate key", "23505")) shouldBe "ActiveRecord::RecordNotUnique"
        ErrorNames.gemName(SQLException("ERROR: null value", "23502")) shouldBe "ActiveRecord::NotNullViolation"
    }

    @Test
    fun `a driver error wrapped in another exception is named by its SQLState`() {
        val wrapped = IllegalStateException("insert failed", SQLException("ERROR: duplicate key", "23505"))

        ErrorNames.gemName(wrapped) shouldBe "ActiveRecord::RecordNotUnique"
    }

    @Test
    fun `a driver error with an unmapped SQLState is a StatementInvalid`() {
        ErrorNames.gemName(SQLException("ERROR: syntax error", "42601")) shouldBe ErrorNames.STATEMENT_INVALID
    }

    @Test
    fun `a plain RuntimeException is Ruby's RuntimeError, a subclass of it is not`() {
        ErrorNames.gemName(RuntimeException("not a User")) shouldBe "RuntimeError"
        ErrorNames.gemName(IllegalStateException("boom")) shouldBe "java.lang.IllegalStateException"
    }

    @Test
    fun `a RuntimeException wrapping a driver error is named by its SQLState`() {
        val wrapped = RuntimeException("insert failed", SQLException("ERROR: duplicate key", "23505"))

        ErrorNames.gemName(wrapped) shouldBe "ActiveRecord::RecordNotUnique"
    }

    @Test
    fun `an unmapped exception keeps its Kotlin class name`() {
        ErrorNames.gemName(IllegalArgumentException("boom")) shouldBe "java.lang.IllegalArgumentException"
    }

    @Test
    fun `each exception of the port is named by its gem class`() {
        val names =
            listOf(
                UnknownAccountException() to "DoubleEntry::UnknownAccount",
                AccountIdentifierTooLongException() to "DoubleEntry::AccountIdentifierTooLongError",
                ScopeIdentifierTooLongException() to "DoubleEntry::ScopeIdentifierTooLongError",
                TransferNotAllowedException() to "DoubleEntry::TransferNotAllowed",
                TransferIsNegativeException() to "DoubleEntry::TransferIsNegative",
                TransferCodeTooLongException() to "DoubleEntry::TransferCodeTooLongError",
                DuplicateAccountException() to "DoubleEntry::DuplicateAccount",
                DuplicateTransferException() to "DoubleEntry::DuplicateTransfer",
                AccountWouldBeSentNegativeException() to "DoubleEntry::AccountWouldBeSentNegative",
                AccountWouldBeSentPositiveException() to "DoubleEntry::AccountWouldBeSentPositiveError",
                MismatchedCurrenciesException() to "DoubleEntry::MismatchedCurrencies",
                MissingAccountException() to "DoubleEntry::MissingAccountError",
            )

        names.forEach { (error, name) -> ErrorNames.gemName(error) shouldBe name }
    }

    @Test
    fun `each locking exception of the port is named by its gem class`() {
        val names =
            listOf(
                LockMustBeOutermostTransactionException() to "DoubleEntry::Locking::LockMustBeOutermostTransaction",
                LockNotHeldException() to "DoubleEntry::Locking::LockNotHeld",
                LockDisasterException() to "DoubleEntry::Locking::LockDisaster",
                LockWaitTimeoutException() to "DoubleEntry::Locking::LockWaitTimeout",
            )

        names.forEach { (error, name) -> ErrorNames.gemName(error) shouldBe name }
    }

    @Test
    fun `a lock wait timeout is named by its gem class, not by the SQLState of its cause`() {
        val timeout = LockWaitTimeoutException(SQLException("ERROR: canceling statement due to lock timeout", "55P03"))

        errorOf(timeout) shouldBe
            JsonObject(
                mapOf(
                    "class" to JsonPrimitive("DoubleEntry::Locking::LockWaitTimeout"),
                    "message" to JsonPrimitive("DoubleEntry::Locking::LockWaitTimeout"),
                ),
            )
    }

    @Test
    fun `an exception of the port thrown without a message has the gem class name as its message`() {
        errorOf(DuplicateTransferException()) shouldBe
            JsonObject(
                mapOf(
                    "class" to JsonPrimitive("DoubleEntry::DuplicateTransfer"),
                    "message" to JsonPrimitive("DoubleEntry::DuplicateTransfer"),
                ),
            )
    }

    @Test
    fun `an exception without a message gets Ruby's default message, the class name`() {
        errorOf(IllegalArgumentException()) shouldBe
            JsonObject(
                mapOf(
                    "class" to JsonPrimitive("java.lang.IllegalArgumentException"),
                    "message" to JsonPrimitive("java.lang.IllegalArgumentException"),
                ),
            )
    }
}
