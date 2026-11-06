package pl.infakt.doubleentry

/**
 * The library's errors: the gem's `DoubleEntry::DoubleEntryError` and its twelve subclasses (errors.rb). Each subclass
 * stands for the gem class of the same name, with `Exception` in place of the name's `Error` suffix, if any.
 *
 * An exception without a message stands for the gem's bare `fail`, whose message is the class name (oracle/unknowns.md,
 * U-03). The locking errors (locking.rb:175-188) are no `DoubleEntryError` in the gem and are not here.
 */
sealed class DoubleEntryException(
    message: String? = null,
) : RuntimeException(message)

/** `UnknownAccount`: an account that was not defined. */
class UnknownAccountException(
    message: String? = null,
) : DoubleEntryException(message)

/** `AccountIdentifierTooLongError`: an account identifier longer than the configured maximum. */
class AccountIdentifierTooLongException(
    message: String? = null,
) : DoubleEntryException(message)

/** `ScopeIdentifierTooLongError`: a scope identity longer than the configured maximum. */
class ScopeIdentifierTooLongException(
    message: String? = null,
) : DoubleEntryException(message)

/** `TransferNotAllowed`: no transfer rule allows the transfer, or it is between an account and itself. */
class TransferNotAllowedException(
    message: String? = null,
) : DoubleEntryException(message)

/** `TransferIsNegative`: a transfer of a negative amount. */
class TransferIsNegativeException(
    message: String? = null,
) : DoubleEntryException(message)

/** `TransferCodeTooLongError`: a transfer code longer than the configured maximum. */
class TransferCodeTooLongException(
    message: String? = null,
) : DoubleEntryException(message)

/** `DuplicateAccount`: a second account with the same identifier. */
class DuplicateAccountException(
    message: String? = null,
) : DoubleEntryException(message)

/** `DuplicateTransfer`: a second transfer rule with the same from, to and code. */
class DuplicateTransferException(
    message: String? = null,
) : DoubleEntryException(message)

/**
 * `AccountWouldBeSentNegative`: a line that would take a positive-only account below zero (line.rb:156-159). Its
 * message is the account.
 */
class AccountWouldBeSentNegativeException(
    message: String? = null,
) : DoubleEntryException(message)

/**
 * `AccountWouldBeSentPositiveError`: a line that would take a negative-only account above zero (line.rb:160-162). Its
 * message is the account.
 */
class AccountWouldBeSentPositiveException(
    message: String? = null,
) : DoubleEntryException(message)

/** `MismatchedCurrencies`: a transfer between accounts of different currencies. */
class MismatchedCurrenciesException(
    message: String? = null,
) : DoubleEntryException(message)

/** `MissingAccountError`: defined by the gem but raised nowhere in it (oracle/unknowns.md, U-08). */
class MissingAccountException(
    message: String? = null,
) : DoubleEntryException(message)
