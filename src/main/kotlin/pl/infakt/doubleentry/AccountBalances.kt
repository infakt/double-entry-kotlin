package pl.infakt.doubleentry

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import pl.infakt.doubleentry.schema.AccountBalancesTable

/**
 * A row of `double_entry_account_balances`: the gem's `DoubleEntry::AccountBalance` (account_balance.rb), the cached
 * current balance of the account [account] in the scope [scope]. [id] is the record's id; [scope] is the account's
 * scope identity, `null` for an unscoped account; [balance] is in the account's currency.
 */
data class AccountBalance(
    val id: Long,
    val account: String,
    val scope: String?,
    val balance: Money,
)

/**
 * The account balance records of [database]: the gem's `AccountBalance` class methods (account_balance.rb). A transfer
 * or a lock writes the records; the library's caller reads them. Each call runs in a transaction of [database] with one
 * attempt, or in the one already open, which it joins.
 */
class AccountBalances internal constructor(
    private val database: Database,
    private val configuration: DoubleEntryConfiguration,
) {
    /**
     * The balance record of [account], or `null` when it has none: `AccountBalance.find_by_account`
     * (account_balance.rb:31-35). The scope matches the account's scope identity, `IS NULL` for an unscoped account,
     * as Active Record's `where(scope: nil)`. With [lock], the row is locked for update until the transaction ends
     * (`lock(true)`).
     */
    fun findByAccount(
        account: AccountInstance,
        lock: Boolean = false,
    ): AccountBalance? =
        transaction(database) {
            maxAttempts = 1
            val table = AccountBalancesTable
            val scope = account.scopeIdentity?.let { table.scope eq it } ?: table.scope.isNull()
            val query =
                table
                    .selectAll()
                    .where { (table.account eq account.identifier) and scope }
                    .orderBy(table.id)
                    .limit(1)
            (if (lock) query.forUpdate() else query).firstOrNull()?.let { row ->
                AccountBalance(
                    id = row[table.id].value,
                    account = row[table.account],
                    scope = row[table.scope],
                    balance = Money(row[table.balance], account.currency),
                )
            }
        }

    /**
     * The scopes of the balance records of the account [identifier] whose balance is at least [minimum]:
     * `AccountBalance.scopes_with_minimum_balance_for_account` (account_balance.rb:50-52), such as the users holding at
     * least a million in their savings. A scope is a record's scope identity, `null` for the record of an unscoped
     * account. The gem compares the minor units only, as its `where('balance >= ?', minimum_balance.fractional)` does:
     * neither the currency of [minimum] nor whether [identifier] names a defined account is checked. The gem gives the
     * scopes in no order (oracle/unknowns.md, U-14); the port gives them in the order of the records' ids.
     */
    fun scopesWithMinimumBalanceForAccount(
        minimum: Money,
        identifier: String,
    ): List<String?> =
        transaction(database) {
            maxAttempts = 1
            val table = AccountBalancesTable
            table
                .select(table.scope)
                .where { (table.account eq identifier) and (table.balance greaterEq minimum.minorUnits) }
                .orderBy(table.id)
                .map { row -> row[table.scope] }
        }

    /**
     * Writes a balance record of [balance] for [account], its timestamps the configuration's clock, and returns its
     * id. A second record for a scoped account violates the unique index on scope and account (SQLState 23505).
     */
    internal fun insert(
        account: AccountInstance,
        balance: Money,
    ): Long =
        transaction(database) {
            maxAttempts = 1
            val now = configuration.clock.now()
            val table = AccountBalancesTable
            table
                .insertAndGetId { row ->
                    row[table.account] = account.identifier
                    row[table.scope] = account.scopeIdentity
                    row[table.balance] = balance.minorUnits
                    row[table.createdAt] = now
                    row[table.updatedAt] = now
                }.value
        }

    /**
     * Sets the balance of the record [recordId] to [balance], its update time the configuration's clock: the gem's
     * `update_attribute :balance` (transfer.rb:104-105), which touches `updated_at`.
     */
    internal fun update(
        recordId: Long,
        balance: Money,
    ) {
        transaction(database) {
            maxAttempts = 1
            val table = AccountBalancesTable
            table.update(where = { table.id eq recordId }) { row ->
                row[table.balance] = balance.minorUnits
                row[table.updatedAt] = configuration.clock.now()
            }
        }
    }
}
