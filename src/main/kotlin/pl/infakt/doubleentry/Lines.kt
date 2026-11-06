package pl.infakt.doubleentry

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import pl.infakt.doubleentry.schema.LinesTable

/**
 * The lines of [database]: the gem's `DoubleEntry::Line` (line.rb), its `save!` of a new line and of one already
 * stored, its readers of the partner and the pair, and its scopes. Each call runs in a transaction of [database] with
 * one attempt, or in the one already open, which it joins. The timestamps are the configuration's clock.
 *
 * Every write first checks that the line keeps its account's balance valid, as `save!` does (line.rb:80-88,
 * 156-163): a positive-only account must not go below zero and a negative-only one must not go above.
 *
 * A line read back has its account and partner account from the stored identifiers and scope identities, as the
 * configuration defines them (line.rb:106-108, 121-123), each giving its balance from [database], its amounts in its
 * account's currency, and its detail when both detail columns are set.
 */
class Lines internal constructor(
    private val database: Database,
    private val configuration: DoubleEntryConfiguration,
) {
    /** The balances of the accounts of the lines read back. */
    private val calculator = BalanceCalculator(database, configuration)

    /**
     * Writes a new line of [account] and returns it with its id: `Line#save!` of a new line. [amount] and [balance]
     * are stored as their minor units, the scope and the partner's scope as the accounts' scope identities.
     *
     * A `null` [code] is left out of the row, as the gem's `code = nil` stores NULL: the column's NOT NULL constraint
     * rejects it (line_spec.rb:32-41).
     *
     * With [DoubleEntryConfiguration.jsonMetadata], [metadata] is the line's `metadata` json column, as the gem's
     * `line.metadata = metadata` stores it (transfer.rb:112), and the line returned has it as [Line.metadata] reads it
     * back. Without json metadata the lines have no such column: [metadata] is not written and the line's is `null`.
     *
     * @throws AccountWouldBeSentNegativeException when [account] is positive-only and [balance] below zero.
     * @throws AccountWouldBeSentPositiveException when [account] is negative-only and [balance] above zero.
     * @throws org.jetbrains.exposed.v1.exceptions.ExposedSQLException with SQLState 23502 when [code] is `null`.
     */
    fun insert(
        account: AccountInstance,
        partnerAccount: AccountInstance,
        code: String?,
        amount: Money,
        balance: Money,
        partnerId: Long? = null,
        detail: Detail? = null,
        metadata: Map<String, Any?>? = null,
    ): Line {
        checkBalanceWillRemainValid(account, balance)
        return transaction(database) {
            maxAttempts = 1
            val now = configuration.clock.now()
            val lines = table()
            val stored = metadata?.takeIf { lines.metadata != null }?.let { plain(jsonObject(it)) }
            // A null code never comes back: the insert fails on the column's NOT NULL constraint first.
            val line =
                Line(0, account, partnerAccount, code.orEmpty(), amount, balance, partnerId, detail, now, now, stored)
            val id =
                lines
                    .insertAndGetId { row ->
                        write(lines, row, line)
                        if (code != null) row[lines.code] = code
                        row[lines.createdAt] = now
                    }.value
            line.copy(id = id)
        }
    }

    /**
     * Writes [line] over the stored line of its id and returns it with its new update time: `Line#save!` of a stored
     * line, as `update_attribute` runs it. The creation time stays as stored.
     *
     * With json metadata the `metadata` column is written from [Line.metadata], not only when it changed (the gem's
     * `update_attribute` writes changed attributes only): a hand-built [Line] with a null `metadata` replaces the
     * stored metadata with NULL. A line read with `find`, returned by a transfer or changed with `copy` is unaffected.
     *
     * @throws AccountWouldBeSentNegativeException when the account is positive-only and the balance below zero.
     * @throws AccountWouldBeSentPositiveException when the account is negative-only and the balance above zero.
     */
    fun update(line: Line): Line {
        checkBalanceWillRemainValid(line.account, line.balance)
        return transaction(database) {
            maxAttempts = 1
            val updated = line.copy(updatedAt = configuration.clock.now())
            val lines = table()
            lines.update(where = { lines.id eq line.id }) { row ->
                write(lines, row, updated)
                row[lines.code] = updated.code
            }
            updated
        }
    }

    /**
     * Writes [balance] and a new update time over the stored line of [line]'s id, and no other column: the gem's
     * `update_attribute(:balance, …)` of `AccountFixer` (validation/account_fixer.rb:12), which saves the changed
     * attribute and `updated_at` only. The other columns, the json metadata among them, stay as stored.
     *
     * @throws AccountWouldBeSentNegativeException when the account is positive-only and [balance] below zero.
     * @throws AccountWouldBeSentPositiveException when the account is negative-only and [balance] above zero.
     */
    internal fun updateBalance(
        line: Line,
        balance: Money,
    ) {
        checkBalanceWillRemainValid(line.account, balance)
        transaction(database) {
            maxAttempts = 1
            val lines = table()
            lines.update(where = { lines.id eq line.id }) { row ->
                row[lines.balance] = balance.minorUnits
                row[lines.updatedAt] = configuration.clock.now()
            }
        }
    }

    /** The stored line [id], or `null`: `Line.find`, which raises `RecordNotFound` where the port gives `null`. */
    fun find(id: Long): Line? = list { lines -> lines.id eq id }.firstOrNull()

    /**
     * The stored line of [line]'s partner: `Line#partner` (line.rb:125-127). `null` when [line] has no partner id or
     * the partner is not stored, where the gem raises `RecordNotFound`.
     */
    fun partnerOf(line: Line): Line? = line.partnerId?.let(::find)

    /**
     * [line] and its partner, the line that decreases its account first: `Line#pair` (line.rb:129-135). A line that is
     * no decrease, such as one of zero, comes second. `null` when [partnerOf] finds no partner.
     */
    fun pairOf(line: Line): Pair<Line, Line>? {
        val partner = partnerOf(line) ?: return null
        return if (line.isDecrease) line to partner else partner to line
    }

    /**
     * The lines with an amount above zero, by id: the gem's scope `Line.credits` (line.rb:60), `amount > 0`.
     *
     * The gem's names, kept as they are: a transfer calls its negative line the credit and its positive line the debit
     * (transfer.rb:107), so this scope lists a transfer's debits, and [debits] its credits (oracle/unknowns.md, U-07).
     */
    fun credits(): List<Line> = list { lines -> lines.amount greater 0L }

    /**
     * The lines with an amount below zero, by id: the gem's scope `Line.debits` (line.rb:61), `amount < 0`. A
     * transfer's credits, by the gem's naming (see [credits]).
     */
    fun debits(): List<Line> = list { lines -> lines.amount less 0L }

    /** The lines of an id above [id], by id: the gem's scope `Line.with_id_greater_than` (line.rb:62). */
    fun withIdGreaterThan(id: Long): List<Line> = list { lines -> lines.id greater id }

    /**
     * At most [limit] lines of an id above [id], by id: one batch of the gem's `find_each` over
     * `Line.with_id_greater_than` (validation/line_check.rb:23, 47-49), the next batch read after the last id of this
     * one.
     */
    internal fun batchWithIdGreaterThan(
        id: Long,
        limit: Int,
    ): List<Line> = list(limit = limit) { lines -> lines.id greater id }

    /**
     * The line of [line]'s account identifier and scope with the highest id below [line]'s, or `null`: the gem's
     * `find_previous_line` (validation/line_check.rb:62-85). The scope is [line]'s scope identity, `IS NULL` for a line
     * without one.
     */
    internal fun previousLine(line: Line): Line? =
        list(limit = 1, order = SortOrder.DESC) { lines ->
            val scope = line.account.scopeIdentity?.let { lines.scope eq it } ?: lines.scope.isNull()
            (lines.account eq line.account.identifier) and scope and (lines.id less line.id)
        }.firstOrNull()

    /**
     * The lines of [account]'s identifier and scope identity, by id: the gem's `AccountFixer#lines_for_account`
     * (validation/account_fixer.rb:23-28). The scope is the account's scope identity, `IS NULL` for an unscoped
     * account, where [forAccount] takes any scope for one.
     */
    internal fun ofAccountAndScope(account: AccountInstance): List<Line> =
        list { lines ->
            val scope = account.scopeIdentity?.let { lines.scope eq it } ?: lines.scope.isNull()
            (lines.account eq account.identifier) and scope
        }

    /**
     * The lines of [account], by id, as the spec's `lines_for_account` reads them (double_entry_spec_helper.rb:3-8):
     * the lines of its identifier, and of its scope identity only when [account] is scoped. The lines of an unscoped
     * account are those of its identifier in any scope.
     */
    fun forAccount(account: AccountInstance): List<Line> =
        list { lines ->
            val identifier: Op<Boolean> = lines.account eq account.identifier
            if (account.isScoped) {
                identifier and (account.scopeIdentity?.let { lines.scope eq it } ?: lines.scope.isNull())
            } else {
                identifier
            }
        }

    /**
     * The check of `Line#save!` (line.rb:156-163): [balance] must not take the positive-only [account] below zero, nor
     * the negative-only one above. The message of either error is the account, as the gem raises it with the account.
     */
    internal fun checkBalanceWillRemainValid(
        account: AccountInstance,
        balance: Money,
    ) {
        if (account.positiveOnly && balance.isNegative) throw AccountWouldBeSentNegativeException(account.toString())
        if (account.negativeOnly && balance.isPositive) throw AccountWouldBeSentPositiveException(account.toString())
    }

    private fun table(): LinesTable = LinesTable.of(configuration.jsonMetadata)

    /** The stored lines where [condition] holds, by id in [order], at most [limit] of them when given. */
    private fun list(
        limit: Int? = null,
        order: SortOrder = SortOrder.ASC,
        condition: (LinesTable) -> Op<Boolean>,
    ): List<Line> =
        transaction(database) {
            maxAttempts = 1
            val lines = table()
            val query =
                lines
                    .selectAll()
                    .where { condition(lines) }
                    .orderBy(lines.id, order)
            (if (limit != null) query.limit(limit) else query).map { row -> line(lines, row) }
        }

    /** The line of [row], its accounts the configuration's (line.rb:106-108, 121-123). */
    private fun line(
        lines: LinesTable,
        row: ResultRow,
    ): Line {
        val accounts = configuration.accounts
        val account = accounts.instance(row[lines.account], null, row[lines.scope], calculator)
        val partnerAccount = accounts.instance(row[lines.partnerAccount], null, row[lines.partnerScope], calculator)
        val detailType = row[lines.detailType]
        val detailId = row[lines.detailId]
        return Line(
            id = row[lines.id].value,
            account = account,
            partnerAccount = partnerAccount,
            code = row[lines.code],
            amount = Money(row[lines.amount], account.currency),
            balance = Money(row[lines.balance], account.currency),
            partnerId = row[lines.partnerId],
            detail = if (detailType != null && detailId != null) Detail(detailType, detailId) else null,
            createdAt = row[lines.createdAt],
            updatedAt = row[lines.updatedAt],
            // Only the json lines have the column: without json metadata, the metadata is not on the line.
            metadata = lines.metadata?.let { column -> row[column]?.let { json -> plain(json) } },
        )
    }

    /** [json] as plain Kotlin values, so the json library stays out of the API: see [Line.metadata]. */
    private fun plain(json: JsonObject): Map<String, Any?> = json.mapValues { (_, value) -> plain(value) }

    private fun plain(json: JsonElement): Any? =
        when (json) {
            is JsonNull -> null
            is JsonObject -> plain(json)
            is JsonArray -> json.map { element -> plain(element) }
            is JsonPrimitive ->
                when {
                    json.isString -> json.content
                    else -> json.booleanOrNull ?: json.longOrNull ?: json.doubleOrNull ?: json.content
                }
        }

    /**
     * [metadata] as the JSON object the `metadata` column stores, as Active Record's json column encodes it: a
     * `String`, a `Boolean` and `null` as themselves, a [Number] as a JSON number (a `Double` or a `Float` that is not
     * finite as `null`), an [Iterable] or an [Array] as an array, a [Map] as an object with each key's `toString()`,
     * and any other value as its `toString()`. See [Line.metadata].
     */
    private fun jsonObject(metadata: Map<*, *>): JsonObject =
        JsonObject(metadata.entries.associate { (key, value) -> key.toString() to json(value) })

    private fun json(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Double -> if (value.isFinite()) JsonPrimitive(value) else JsonNull
            is Float -> if (value.isFinite()) JsonPrimitive(value) else JsonNull
            is Number -> JsonPrimitive(value)
            is Map<*, *> -> jsonObject(value)
            is Iterable<*> -> JsonArray(value.map { each -> json(each) })
            is Array<*> -> JsonArray(value.map { each -> json(each) })
            else -> JsonPrimitive(value.toString())
        }

    /**
     * Sets every column of [row] from [line] but the id, the code and the creation time: the `metadata` column, which
     * only the json lines have, as [Line.metadata].
     */
    private fun write(
        lines: LinesTable,
        row: UpdateBuilder<*>,
        line: Line,
    ) {
        row[lines.account] = line.account.identifier
        row[lines.scope] = line.account.scopeIdentity
        row[lines.amount] = line.amount.minorUnits
        row[lines.balance] = line.balance.minorUnits
        row[lines.partnerId] = line.partnerId
        row[lines.partnerAccount] = line.partnerAccount.identifier
        row[lines.partnerScope] = line.partnerAccount.scopeIdentity
        row[lines.detailType] = line.detail?.type
        row[lines.detailId] = line.detail?.id
        lines.metadata?.let { column -> row[column] = line.metadata?.let(::jsonObject) }
        row[lines.updatedAt] = line.updatedAt
    }
}
