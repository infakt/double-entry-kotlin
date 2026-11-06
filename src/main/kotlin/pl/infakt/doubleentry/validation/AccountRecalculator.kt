package pl.infakt.doubleentry.validation

import pl.infakt.doubleentry.AccountInstance

/**
 * Corrects the stored balances of an account that [LineCheck.perform] found wrong: the gem's `fixer`, which the check
 * calls as `fixer.recalculate_account(account)` for each incorrect account (validation/line_check.rb:33). The gem's
 * fixer is `AccountFixer` (validation/account_fixer.rb).
 */
fun interface AccountRecalculator {
    /** Recalculates the running balances of [account]'s lines and its balance record. */
    fun recalculateAccount(account: AccountInstance)
}
