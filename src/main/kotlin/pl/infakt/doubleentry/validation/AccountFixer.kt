package pl.infakt.doubleentry.validation

import pl.infakt.doubleentry.AccountBalances
import pl.infakt.doubleentry.AccountInstance
import pl.infakt.doubleentry.Lines
import pl.infakt.doubleentry.Locking
import pl.infakt.doubleentry.Money

/**
 * The fixer of the line check: the gem's `DoubleEntry::Validation::AccountFixer` (validation/account_fixer.rb), which
 * recalculates the running balances of an account's lines and its balance record. Hand it to [LineCheck.perform] to
 * correct each account the check finds wrong.
 *
 * Each call locks the account, as `DoubleEntry.lock_accounts` takes it, so it must run outside a transaction of the
 * caller unless [pl.infakt.doubleentry.DoubleEntryConfiguration.runningInsideTransactionalFixtures] allows one.
 */
class AccountFixer internal constructor(
    private val lines: Lines,
    private val locking: Locking,
    private val accountBalances: AccountBalances,
) : AccountRecalculator {
    /**
     * Inside a lock of [account], walks the lines of its identifier and scope identity (`IS NULL` for an unscoped
     * account) by id, adding up their amounts from zero in the account's currency; each line whose balance is not that
     * sum gets it, written as `update_attribute(:balance, …)` writes it (its balance and update time only, so its
     * metadata stays as stored), and the others are left as they are. The account's balance record is then set to the
     * sum, and written only when it differs: `AccountFixer#recalculate_account` (account_fixer.rb:6-19).
     *
     * @throws pl.infakt.doubleentry.LockMustBeOutermostTransactionException when a transaction of the database is
     *   already open.
     * @throws pl.infakt.doubleentry.LockWaitTimeoutException when the database gave up waiting for a lock.
     * @throws pl.infakt.doubleentry.AccountWouldBeSentNegativeException when a positive-only account's recalculated
     *   balance of a line is below zero; the lock's transaction rolls back.
     * @throws pl.infakt.doubleentry.AccountWouldBeSentPositiveException when a negative-only account's recalculated
     *   balance of a line is above zero; the lock's transaction rolls back.
     */
    override fun recalculateAccount(account: AccountInstance) {
        locking.lockAccounts(listOf(account)) {
            var recalculatedBalance = Money.zero(account.currency)
            lines.ofAccountAndScope(account).forEach { line ->
                recalculatedBalance += line.amount
                if (line.balance != recalculatedBalance) lines.updateBalance(line, recalculatedBalance)
            }
            updateBalanceForAccount(account, recalculatedBalance)
        }
    }

    /**
     * `update_balance_for_account` (account_fixer.rb:30-33): the locked account's record gets [balance]. A record that
     * already has it is not written, as `update_attribute` saves nothing and touches no `updated_at` without a change.
     */
    private fun updateBalanceForAccount(
        account: AccountInstance,
        balance: Money,
    ) {
        val record = locking.balanceForLockedAccount(account)
        if (record.balance != balance) accountBalances.update(record.id, balance)
    }
}
