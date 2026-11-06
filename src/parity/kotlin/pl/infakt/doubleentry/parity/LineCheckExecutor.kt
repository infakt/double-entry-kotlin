package pl.infakt.doubleentry.parity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import pl.infakt.doubleentry.Money
import pl.infakt.doubleentry.schema.AccountBalancesTable
import pl.infakt.doubleentry.schema.LineChecksTable
import pl.infakt.doubleentry.validation.LineCheckRecord

/**
 * The line-check area: `line_check`, `create_line_check`, `last_line_id_checked`, `sql_update` and
 * `account_balance_record` through `DoubleEntry.lineCheck` (oracle/README.md, "Line checks"). The scenarios define
 * their accounts and transfer rules, transfer and move the clock first, so every other op runs on a scenario of the
 * balances area ([BalancesExecutor]), on the same database and configuration; the effects are its tables.
 *
 * The log of a check, as a value and as the `log` column of `effects.double_entry_line_checks`, has the two inspect
 * lines of each "Error on line" entry masked as the recorder masks the gem's ([masked]): the port writes
 * `Line.toString` there (docs/deviations.md, X-12). `line_check` with `fixer: true` is a replay error in this area;
 * the account-fixer area ([startWithFixer]) runs it with the fixer.
 */
object LineCheckExecutor : AreaExecutor {
    override fun start(input: JsonObject): ScenarioRun = Run(BalancesExecutor.start(input), withFixer = false)

    /**
     * A scenario of the account-fixer area ([AccountFixerExecutor]): the ops of this area, where `line_check` with
     * `fixer: true` hands the check `DoubleEntry.accountFixer`, the gem's `AccountFixer.new`.
     */
    internal fun startWithFixer(input: JsonObject): ScenarioRun = Run(BalancesExecutor.start(input), withFixer = true)

    /** The tables `sql_update` may change: the two whose rows have a `balance` (oracle/README.md, "Line checks"). */
    private val balanceTables = setOf("double_entry_lines", AccountBalancesTable.tableName)

    /** The two inspect lines that end an "Error on line" entry, after the entry's heading and its closing line. */
    private val inspectLines = Regex("(Error on line #\\d+: [^\\n]*\\n\\*{33}\\n)  [^\\n]*\\n  [^\\n]*\\n")

    /**
     * [log] with the two inspect lines of each "Error on line" entry replaced by one line, `  <masked>`, as the
     * recorder writes them (oracle/README.md, "The masked region of the log"). The rest of the log is kept as is.
     */
    fun masked(log: String): String = inspectLines.replace(log) { match -> match.groupValues[1] + "  <masked>\n" }

    private class Run(
        private val balances: BalancesExecutor.Run,
        private val withFixer: Boolean,
    ) : ScenarioRun {
        private val doubleEntry = balances.doubleEntry

        private val lineCheck = doubleEntry.lineCheck

        override fun run(step: JsonObject): JsonElement =
            when (step.string("op")) {
                "line_check" -> perform(step)
                "create_line_check" -> createLineCheck(step)
                "last_line_id_checked" -> JsonPrimitive(lineCheck.lastLineIdChecked())
                "sql_update" -> sqlUpdate(step)
                "account_balance_record" -> accountBalanceRecord(step)
                else -> balances.run(step)
            }

        override fun tables(): Map<String, JsonElement> =
            balances.tables().mapValues { (name, rows) ->
                if (name == LineChecksTable.tableName && rows is JsonArray) JsonArray(rows.map(::maskedRow)) else rows
            }

        /**
         * `LineCheck.perform!(fixer: nil)`: the check, or `null` when there was no new line. `fixer: true`, the gem's
         * `AccountFixer.new`, is the account-fixer area's: there the check is handed `DoubleEntry.accountFixer`.
         */
        private fun perform(step: JsonObject): JsonElement {
            val fixer = flag(step, "fixer")
            if (fixer && !withFixer) throw ReplayError(".fixer", "false, the line-check area's", "true")
            val recalculator = if (fixer) doubleEntry.accountFixer else null
            return lineCheck.perform(recalculator)?.let(::json) ?: JsonNull
        }

        /** `LineCheck.create!(last_line_id:, errors_found:, log:)`, its timestamps the frozen clock. */
        private fun createLineCheck(step: JsonObject): JsonElement =
            json(lineCheck.create(long(step, "last_line_id"), flag(step, "errors_found"), step.string("log")))

        /**
         * `<model>.order(:id).limit(1).update_all("balance = balance + <delta>")`: adds `delta` to the `balance` of the
         * first row by id of `table`, past the library, as the spec corrupts a balance. The value is `null`.
         */
        private fun sqlUpdate(step: JsonObject): JsonElement {
            val table = step.string("table")
            if (table !in balanceTables) throw ReplayError(".table", "one of $balanceTables", "\"$table\"")
            val delta = long(step, "delta")
            transaction(doubleEntry.database) {
                maxAttempts = 1
                exec("UPDATE $table SET balance = balance + $delta WHERE id = (SELECT MIN(id) FROM $table)")
            }
            return JsonNull
        }

        /**
         * `AccountBalance.find_by_account(DoubleEntry.account(...))&.balance` for the account reference flat in the
         * step: the Money of the record, or `null` when the account has none.
         */
        private fun accountBalanceRecord(step: JsonObject): JsonElement {
            val record = doubleEntry.accountBalances.findByAccount(balances.account(step))
            return record?.let { json(it.balance) } ?: JsonNull
        }

        /** A row of `double_entry_line_checks` with its `log` masked. */
        private fun maskedRow(row: JsonElement): JsonElement {
            val columns = row as? JsonObject ?: return row
            val log = (columns["log"] as? JsonPrimitive)?.takeIf { it.isString } ?: return row
            return JsonObject(columns + ("log" to JsonPrimitive(masked(log.content))))
        }

        private fun long(
            step: JsonObject,
            key: String,
        ): Long {
            val value = step[key]
            return (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                ?: throw ReplayError(".$key", "an integer", value?.toString() ?: MISSING)
        }

        private fun flag(
            step: JsonObject,
            key: String,
        ): Boolean {
            val value = step[key]
            return (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: throw ReplayError(".$key", "a boolean", value?.toString() ?: MISSING)
        }

        /** A check as the oracle writes a `Validation::LineCheck`: `{"errors_found", "id", "last_line_id", "log"}`. */
        private fun json(check: LineCheckRecord): JsonElement =
            JsonObject(
                mapOf(
                    "errors_found" to JsonPrimitive(check.errorsFound),
                    "id" to JsonPrimitive(check.id),
                    "last_line_id" to JsonPrimitive(check.lastLineId),
                    "log" to JsonPrimitive(check.log?.let { log -> masked(log) }),
                ),
            )

        /** Money as the oracle writes it: `{"cents", "currency"}`. */
        private fun json(money: Money): JsonElement =
            JsonObject(
                mapOf(
                    "cents" to JsonPrimitive(money.minorUnits),
                    "currency" to JsonPrimitive(money.currency.currencyCode),
                ),
            )
    }
}
