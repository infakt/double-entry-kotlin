package pl.infakt.doubleentry

import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import pl.infakt.doubleentry.schema.LinesTable

/**
 * The balance of an account from its lines on [database]: the gem's `DoubleEntry::BalanceCalculator`
 * (balance_calculator.rb). Each call runs in a transaction of [database] with one attempt, or in the one already open,
 * which it joins.
 */
internal class BalanceCalculator(
    private val database: Database,
    private val configuration: DoubleEntryConfiguration,
) {
    /**
     * The current or historic balance of [account], in its currency: `BalanceCalculator.calculate`
     * (balance_calculator.rb:16-33). The lines taken are those of the account's identifier and, when the account has a
     * scope identity, of that scope (balance_calculator.rb:74-76, 93); of those:
     * - with [at], the lines created at or before it;
     * - with [from] and [to] and no [at], the lines created between them, both included; [from] or [to] alone is no
     *   range;
     * - with [code] or [codes], the lines of any of them, [code] being one more of [codes].
     *
     * With a range or a code, the balance is the sum of the lines' amounts, zero without a line; otherwise it is the
     * running balance of the last line by id, zero without one.
     */
    fun calculate(
        account: AccountInstance,
        from: Instant? = null,
        to: Instant? = null,
        at: Instant? = null,
        code: String? = null,
        codes: List<String> = emptyList(),
    ): Money {
        // `(args[:codes].to_a << args[:code]).compact` (balance_calculator.rb:56).
        val allCodes = codes + listOfNotNull(code)
        // `!!(from && to && !at?)` (balance_calculator.rb:66-68).
        val range = if (from != null && to != null && at == null) Pair(from, to) else null
        return transaction(database) {
            maxAttempts = 1
            val lines = LinesTable.of(configuration.jsonMetadata)
            // The relation of `RelationBuilder#build` (balance_calculator.rb:88-95).
            var condition: Op<Boolean> = lines.account eq account.identifier
            if (at != null) condition = condition and (lines.createdAt lessEq at)
            range?.let { (start, end) -> condition = condition and lines.createdAt.between(start, end) }
            if (allCodes.isNotEmpty()) condition = condition and (lines.code inList allCodes)
            account.scopeIdentity?.let { scope -> condition = condition and (lines.scope eq scope) }
            val minorUnits =
                if (range != null || allCodes.isNotEmpty()) {
                    val sum = lines.amount.sum()
                    lines
                        .select(sum)
                        .where { condition }
                        .single()[sum]
                } else {
                    lines
                        .select(lines.balance)
                        .where { condition }
                        .orderBy(lines.id, SortOrder.DESC)
                        .limit(1)
                        .firstOrNull()
                        ?.get(lines.balance)
                }
            Money(minorUnits ?: 0L, account.currency)
        }
    }
}
