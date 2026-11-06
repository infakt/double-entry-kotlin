package pl.infakt.doubleentry

import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Moves money between accounts on [database]: the gem's `Transfer.transfer` and `Transfer#process` (transfer.rb:16-22,
 * 78-121). A transfer writes two lines, the credit of the account it comes from and the debit of the account it goes
 * to, and the two accounts' balances, inside a lock of both accounts that [locking] takes.
 */
internal class Transfers(
    database: Database,
    private val configuration: DoubleEntryConfiguration,
    private val locking: Locking,
) {
    private val balances = AccountBalances(database, configuration)

    private val lines = Lines(database, configuration)

    private val lineMetadata = LineMetadataRows(database, configuration)

    /**
     * Transfers [amount] from [from] to [to] under [code], the lines about [detail], and returns the credit and the
     * debit (transfer.rb:16-22, 78-93). The checks run in the gem's order, before anything is locked or written.
     *
     * Without [DoubleEntryConfiguration.jsonMetadata], a [metadata] goes to `double_entry_line_metadata` inside the
     * lock, after the lines (transfer.rb:91-94, 123-130): see [LineMetadataRows.insert]. With it, [metadata] is the
     * `metadata` json column of both lines and no row of `double_entry_line_metadata` is written (transfer.rb:112): see
     * [Lines.insert].
     *
     * [amount] is in the accounts' currency: the balances are reckoned with it, as the gem reckons them with its
     * `Money`. [Lines] checks each line as the gem's `check_balance_will_remain_valid` does (line.rb:156-163), inside
     * the lock, so a line that fails the check rolls back the balances already updated.
     *
     * @throws TransferIsNegativeException when [amount] is below zero.
     * @throws TransferNotAllowedException when no rule allows [from] → [to] under [code], or when [from] and [to] are
     *   the same account in the same scope, with the message `from account and to account are identical`.
     * @throws MismatchedCurrenciesException when the accounts' currencies differ, with the message
     *   `Mismatched currency (<to's> <> <from's>)`.
     * @throws CurrencyMismatchException when [amount] is in another currency than the accounts.
     * @throws AccountWouldBeSentNegativeException when the credit would take a positive-only [from] below zero, or the
     *   debit a positive-only [to].
     * @throws AccountWouldBeSentPositiveException when the credit would take a negative-only [from] above zero, or the
     *   debit a negative-only [to].
     * @throws LockMustBeOutermostTransactionException when a transaction of the database is already open.
     * @throws LockNotHeldException inside a lock that does not lock both accounts.
     */
    fun transfer(
        amount: Money,
        from: AccountInstance,
        to: AccountInstance,
        code: String?,
        detail: Detail?,
        metadata: Map<String, Any>? = null,
    ): Pair<Line, Line> {
        if (amount.isNegative) throw TransferIsNegativeException()
        val rule = configuration.transfers.findOrThrow(from.identifier, to.identifier, code)
        if (from.scopeIdentity == to.scopeIdentity && from.identifier == to.identifier) {
            throw TransferNotAllowedException("from account and to account are identical")
        }
        if (to.currency != from.currency) {
            throw MismatchedCurrenciesException(
                "Mismatched currency (${to.currency.currencyCode} <> ${from.currency.currencyCode})",
            )
        }
        val created =
            locking.lockAccounts(listOf(from, to)) {
                val lineJson = metadata?.takeIf { configuration.jsonMetadata }
                val (credit, debit) = createLines(amount, rule.code, detail, from, to, lineJson)
                if (metadata != null && !configuration.jsonMetadata) lineMetadata.insert(credit, debit, metadata)
                credit to debit
            }
        // Only a rollback of the block returns null, and createLines never rolls back.
        return checkNotNull(created) { "the lock of a transfer was rolled back" }
    }

    /**
     * Inside the lock: updates both balances, writes the credit, then the debit with the credit as its partner, then
     * sets the debit as the credit's partner (transfer.rb:95-121). Both lines have [metadata] as their json metadata,
     * `null` without json metadata (transfer.rb:112).
     */
    private fun createLines(
        amount: Money,
        code: String,
        detail: Detail?,
        from: AccountInstance,
        to: AccountInstance,
        metadata: Map<String, Any>?,
    ): Pair<Line, Line> {
        val creditBalance = locking.balanceForLockedAccount(from)
        val debitBalance = locking.balanceForLockedAccount(to)
        val credited = creditBalance.balance - amount
        val debited = debitBalance.balance + amount
        balances.update(creditBalance.id, credited)
        balances.update(debitBalance.id, debited)

        val credit = lines.insert(from, to, code, -amount, credited, null, detail, metadata)
        val debit = lines.insert(to, from, code, amount, debited, credit.id, detail, metadata)
        return lines.update(credit.copy(partnerId = debit.id)) to debit
    }
}
