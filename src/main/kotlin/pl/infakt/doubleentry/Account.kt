package pl.infakt.doubleentry

import java.util.Currency

/**
 * An account definition: the gem's `DoubleEntry::Account` (account.rb:139-159). An account is scoped when it has a
 * [scopeIdentifier], which turns a scope (such as a user) into the scope identity of an [AccountInstance].
 */
class Account(
    val identifier: String,
    val scopeIdentifier: ((Any) -> Any?)? = null,
    val positiveOnly: Boolean = false,
    val negativeOnly: Boolean = false,
    val currency: Currency,
) {
    /** Whether the account has a scope identifier: the gem's `scoped?` (account.rb:156-158). */
    val isScoped: Boolean
        get() = scopeIdentifier != null
}

/**
 * The accounts defined so far: the gem's `Account::Set` (account.rb:28-66), keyed by identifier in definition order.
 * [accountIdentifierMaxLength] and [defaultCurrency] are read at each definition and [scopeIdentifierMaxLength] at
 * each [account], as the gem reads `Account.account_identifier_max_length`, `Money.default_currency` and
 * `Account.scope_identifier_max_length`.
 */
class Accounts internal constructor(
    private val accountIdentifierMaxLength: () -> Int? = { null },
    private val scopeIdentifierMaxLength: () -> Int? = { null },
    private val defaultCurrency: () -> Currency = { Currency.getInstance("USD") },
) {
    private val accounts = LinkedHashMap<String, Account>()

    /**
     * Defines the account [identifier] and returns it (account.rb:33-41, 143-153). Without a [currency], the account
     * takes the default currency of the moment.
     *
     * @throws AccountIdentifierTooLongException when [identifier] has more characters than allowed
     *   (account.rb:149-152).
     * @throws DuplicateAccountException when an account with [identifier] is defined already (account.rb:35-36).
     */
    fun define(
        identifier: String,
        scopeIdentifier: ((Any) -> Any?)? = null,
        positiveOnly: Boolean = false,
        negativeOnly: Boolean = false,
        currency: Currency? = null,
    ): Account {
        val maxLength = accountIdentifierMaxLength()
        // Ruby's String#length counts characters, not UTF-16 units.
        if (maxLength != null && identifier.codePointCount(0, identifier.length) > maxLength) {
            throw AccountIdentifierTooLongException(
                "account identifier '$identifier' is too long. Please limit it to $maxLength characters.",
            )
        }
        val account = Account(identifier, scopeIdentifier, positiveOnly, negativeOnly, currency ?: defaultCurrency())
        if (identifier in accounts) throw DuplicateAccountException()
        accounts[identifier] = account
        return account
    }

    /**
     * The account [identifier], when it is defined and is scoped exactly when [scoped] is (account.rb:43-51).
     *
     * @throws UnknownAccountException otherwise, with the message `account: <identifier> scoped?: <scoped>`.
     */
    fun find(
        identifier: String,
        scoped: Boolean,
    ): Account =
        findWithoutScope(identifier)?.takeIf { it.isScoped == scoped }
            ?: throw UnknownAccountException("account: $identifier scoped?: $scoped")

    /** The account [identifier], scoped or not, or `null` when none is defined (account.rb:53-55). */
    fun findWithoutScope(identifier: String): Account? = accounts[identifier]

    /**
     * The currency of the account [identifier], scoped or not, or `null` when none is defined: the gem's
     * `Account.currency` (account.rb:22-24).
     */
    fun currency(identifier: String): Currency? = findWithoutScope(identifier)?.currency

    /** Every account, in the order of definition (account.rb:57-59). */
    fun all(): List<Account> = accounts.values.toList()

    /**
     * The instance of the account [identifier] for [scope], or for [scopeIdentity] given as is: the gem's
     * `Account.account` (account.rb:16-19). The account is looked for as scoped when [scope] or [scopeIdentity] is
     * present, neither `null` nor a blank string, as the gem asks `present?`.
     *
     * @throws UnknownAccountException when no account [identifier] is defined, or it is scoped and neither is
     *   present, or it is unscoped and one is.
     * @throws ScopeIdentifierTooLongException when the scope identity has more characters than allowed.
     */
    fun account(
        identifier: String,
        scope: Any? = null,
        scopeIdentity: String? = null,
    ): AccountInstance = instance(identifier, scope, scopeIdentity, calculator = null)

    /**
     * [account], the instance with its balance from [calculator] (AccountInstance.balance), or without a balance when
     * [calculator] is `null`.
     */
    internal fun instance(
        identifier: String,
        scope: Any?,
        scopeIdentity: String?,
        calculator: BalanceCalculator?,
    ): AccountInstance {
        val account = find(identifier, isPresent(scope) || isPresent(scopeIdentity))
        return AccountInstance(account, scope, scopeIdentity, scopeIdentifierMaxLength(), calculator)
    }
}

/** Rails' `present?` for the scopes the port passes: anything but `null` and a blank string. */
private fun isPresent(value: Any?): Boolean =
    when (value) {
        null -> false
        is CharSequence -> value.isNotBlank()
        else -> true
    }
