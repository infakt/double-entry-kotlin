package pl.infakt.doubleentry

import java.util.Currency
import java.util.Objects
import kotlin.time.Instant

/**
 * An account in a scope: the gem's `Account::Instance` (account.rb:68-138). Two instances are equal when they have
 * the same identifier and scope identity; they sort by scope identity, then identifier, an unscoped one as an empty
 * scope identity.
 *
 * An instance that [DoubleEntry.account] gives, or the account of a line that [DoubleEntry.lines] reads, has its
 * [balance] from the facade's database; one made by [Accounts.account] alone has none (docs/deviations.md, X-10).
 * Where the balance comes from plays no part in equality.
 *
 * @throws ScopeIdentifierTooLongException when the scope identity has more characters than
 *   [scopeIdentifierMaxLength] (account.rb:132-137).
 */
class AccountInstance internal constructor(
    val account: Account,
    val scope: Any? = null,
    scopeIdentity: String? = null,
    scopeIdentifierMaxLength: Int? = null,
    private val calculator: BalanceCalculator? = null,
) : Comparable<AccountInstance> {
    /**
     * The given scope identity, kept as is even when blank, or else what the account's scope identifier makes of
     * [scope] as a string; `null` for an unscoped account (account.rb:79-85).
     */
    val scopeIdentity: String? = scopeIdentity ?: identityOf(account, scope)

    val identifier: String
        get() = account.identifier

    val isScoped: Boolean
        get() = account.isScoped

    val positiveOnly: Boolean
        get() = account.positiveOnly

    val negativeOnly: Boolean
        get() = account.negativeOnly

    val currency: Currency
        get() = account.currency

    init {
        val identity = this.scopeIdentity
        val maxLength = scopeIdentifierMaxLength
        // Ruby's String#length counts characters, not UTF-16 units.
        if (identity != null && maxLength != null && identity.codePointCount(0, identity.length) > maxLength) {
            throw ScopeIdentifierTooLongException(
                "scope identifier '$identity' is too long. Please limit it to $maxLength characters.",
            )
        }
    }

    /**
     * The current or historic balance of the account, in its currency: the gem's `Account::Instance#balance`
     * (account.rb:96-98), as [DoubleEntry.balance] reckons it. With [at], the running balance at that time; with
     * [from] and [to] and no [at], the sum of the amounts of the lines created between them, both included; with
     * [code] or [codes], the sum of the amounts of the lines of any of them; otherwise the running balance of the last
     * line. Zero without a line.
     *
     * @throws IllegalStateException when the instance was not made by a facade, so it has no database (X-10).
     */
    fun balance(
        from: Instant? = null,
        to: Instant? = null,
        at: Instant? = null,
        code: String? = null,
        codes: List<String> = emptyList(),
    ): Money {
        val calculator =
            checkNotNull(calculator) {
                "$this has no database: take the account instance from DoubleEntry.account for its balance"
            }
        return calculator.calculate(this, from, to, at, code, codes)
    }

    override fun equals(other: Any?): Boolean =
        other is AccountInstance && identifier == other.identifier && scopeIdentity == other.scopeIdentity

    override fun hashCode(): Int = Objects.hash(identifier, scopeIdentity)

    override fun compareTo(other: AccountInstance): Int =
        compareValuesBy(this, other, { it.scopeIdentity ?: "" }, { it.identifier })

    /** The gem's `to_s` (account.rb:117-119): `#{Account account: <identifier> scope: <scope> currency: <code>}`. */
    override fun toString(): String =
        "#{Account account: $identifier scope: ${scope ?: ""} currency: ${currency.currencyCode}}"
}

/**
 * What the scope identifier of [account] makes of [scope], as a string, with `null` as `""` (Ruby's `nil.to_s`).
 * `null` for an unscoped account, or a scoped one without a scope.
 */
private fun identityOf(
    account: Account,
    scope: Any?,
): String? {
    val scopeIdentifier = account.scopeIdentifier ?: return null
    if (scope == null) return null
    return scopeIdentifier(scope)?.toString() ?: ""
}
