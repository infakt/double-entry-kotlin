package pl.infakt.doubleentry.parity

import java.util.Currency
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.v1.jdbc.Database
import pl.infakt.doubleentry.Account
import pl.infakt.doubleentry.AccountInstance
import pl.infakt.doubleentry.Detail
import pl.infakt.doubleentry.DoubleEntry
import pl.infakt.doubleentry.DoubleEntryConfiguration
import pl.infakt.doubleentry.Line
import pl.infakt.doubleentry.Money
import pl.infakt.doubleentry.TransferRule
import pl.infakt.doubleentry.schema.DoubleEntrySchema

/**
 * The lines area: `seed_line`, `line_save`, `line_attr`, `line_partner`, `line_pair` and `lines_scope` on a new
 * PostgreSQL database per scenario with the generator shape for `input.schema`, and the clock frozen at `input.clock`
 * (oracle/README.md, "Ops"). The scenarios define their accounts and transfer rules and transfer first, so the area
 * also runs `define_account` (with the scopes `none`, `identity`, `user_id` and `user_only`), `define_transfer` and
 * `transfer`, and the configuration area's ops, on the same configuration. The effects are the rows of the four
 * tables ([ledgerTables]).
 *
 * The port's exceptions propagate as they are; the replay names them through [ErrorNames], so a line without a code,
 * rejected by PostgreSQL with SQLState 23502, is the gem's `ActiveRecord::NotNullViolation`.
 */
object LinesExecutor : AreaExecutor {
    /** The clock of a scenario without `input.clock` (oracle/README.md, "Adding a scenario"). */
    private const val DEFAULT_CLOCK = "2024-01-01T00:00:00Z"

    override fun start(input: JsonObject): ScenarioRun {
        val schema = input["schema"] as? JsonObject
        val shape = (schema?.get("shape") as? JsonPrimitive)?.content ?: "generator"
        require(shape == "generator") { "input.schema: the port creates the generator shape only, got $shape" }
        val jsonMetadata = (schema?.get("json_metadata") as? JsonPrimitive)?.booleanOrNull ?: false
        val frozen = Instant.parse((input["clock"] as? JsonPrimitive)?.content ?: DEFAULT_CLOCK)
        val database = TestDatabases.empty()
        DoubleEntrySchema.create(database, jsonMetadata)
        val config =
            DoubleEntryConfiguration().apply {
                clock =
                    object : Clock {
                        override fun now(): Instant = frozen
                    }
            }
        return Run(database, config, jsonMetadata)
    }

    /** The recorder's `User = Struct.new(:id)`: the scope `{"user": <id>}`. */
    private data class User(
        val id: Int,
    )

    /** The scope identifiers `define_account` names, as the recorder's `SCOPES` writes them. */
    private val scopes: Map<String, (Any) -> Any?> =
        mapOf(
            "identity" to { value: Any -> value },
            "user_id" to { user: Any -> (user as User).id },
            "user_only" to { user: Any -> if (user is User) user.id else throw RuntimeException("not a User") },
        )

    /** The recorder's `Invoice` model: the type of a `detail` `{"invoice": <id>}` (oracle/README.md, "Ops"). */
    private const val INVOICE = "Invoice"

    private class Run(
        private val database: Database,
        private val config: DoubleEntryConfiguration,
        private val jsonMetadata: Boolean,
    ) : ScenarioRun {
        private val configuration = ConfigurationExecutor.start(config)

        private val doubleEntry = DoubleEntry(database, config)

        private val lines = doubleEntry.lines

        override fun run(step: JsonObject): JsonElement =
            when (step.string("op")) {
                "define_account" -> json(define(step))
                "define_transfer" -> json(defineTransfer(step))
                "transfer" -> transfer(step)
                "seed_line" -> seedLine(step)
                "line_save" -> lineSave(step)
                "line_attr" -> lineAttr(step)
                "line_partner" -> json(partner(stored(step)))
                "line_pair" -> linePair(step)
                "lines_scope" -> linesScope(step)
                else -> configuration.run(step)
            }

        override fun tables(): Map<String, JsonElement> = ledgerTables(database, jsonMetadata)

        /**
         * `DoubleEntry.transfer(Money.new(amount), from:, to:, code:, detail:)`, the amount in the default currency;
         * the value is `[credit, debit]`.
         */
        private fun transfer(step: JsonObject): JsonElement {
            val amount = Money(long(step, "amount"), config.defaultCurrency)
            val from = account(reference(step["from"], ".from"))
            val to = account(reference(step["to"], ".to"))
            val code = if ("code" in step) step.string("code") else null
            val detail = step["detail"]?.let(::detail)
            val (credit, debit) = doubleEntry.transfer(amount, from, to, code, detail)
            return JsonArray(listOf(json(credit), json(debit)))
        }

        /**
         * `DoubleEntry::Line.create!(account:, partner_account:, amount:, balance:, code:)`, the amounts in the
         * account's currency; the value is the `Line`. A missing or `null` code is left NULL, which the column rejects.
         */
        private fun seedLine(step: JsonObject): JsonElement {
            val account = account(reference(step["account"], ".account"))
            val partner = account(reference(step["partner_account"], ".partner_account"))
            val amount = Money(long(step, "amount"), account.currency)
            val balance = Money(long(step, "balance"), account.currency)
            return json(lines.insert(account, partner, code(step), amount, balance))
        }

        /**
         * `DoubleEntry::Line.new(...).save` with the attributes the step gives; the value is `true`. The check of the
         * balance runs first, as `save` runs it (line.rb:80-83). The port writes a line only with its partner account
         * and its amount: a step without them that passes the check, where the gem's insert fails on a NOT NULL column,
         * is a [ReplayError].
         */
        private fun lineSave(step: JsonObject): JsonElement {
            val account = account(reference(step["account"], ".account"))
            val balance = Money(long(step, "balance"), account.currency)
            if ("partner_account" !in step || "amount" !in step) {
                lines.checkBalanceWillRemainValid(account, balance)
                val missing = if ("partner_account" !in step) ".partner_account" else ".amount"
                throw ReplayError(missing, "a partner account and an amount for a line that passes the check", MISSING)
            }
            val partner = account(reference(step["partner_account"], ".partner_account"))
            val amount = Money(long(step, "amount"), account.currency)
            lines.insert(account, partner, code(step), amount, balance)
            return JsonPrimitive(true)
        }

        /** `Line.find(line).<attr>`, or the raw column `Line.find(line)[column]`; exactly one of the two is given. */
        private fun lineAttr(step: JsonObject): JsonElement {
            val line = stored(step)
            return when {
                "attr" in step && "column" !in step -> attribute(line, step.string("attr"))
                "column" in step && "attr" !in step -> column(line, step.string("column"))
                else -> throw ReplayError(".attr", "exactly one of attr and column", step.toString())
            }
        }

        /** One of Line's readers (oracle/README.md, "Ops"). */
        private fun attribute(
            line: Line,
            attr: String,
        ): JsonElement =
            when (attr) {
                "amount" -> json(line.amount)
                "balance" -> json(line.balance)
                "code" -> JsonPrimitive(line.code)
                "account" -> json(line.account)
                "partner_account" -> json(line.partnerAccount)
                "currency" -> JsonPrimitive(line.account.currency.currencyCode)
                "increase?" -> JsonPrimitive(line.isIncrease)
                "decrease?" -> JsonPrimitive(line.isDecrease)
                else -> throw ReplayError(".attr", "a reader of Line", "\"$attr\"")
            }

        /** A raw column of the line, as stored (oracle/README.md, "Ops"). */
        private fun column(
            line: Line,
            column: String,
        ): JsonElement =
            when (column) {
                "id" -> JsonPrimitive(line.id)
                "account" -> JsonPrimitive(line.account.identifier)
                "scope" -> JsonPrimitive(line.account.scopeIdentity)
                "code" -> JsonPrimitive(line.code)
                "amount" -> JsonPrimitive(line.amount.minorUnits)
                "balance" -> JsonPrimitive(line.balance.minorUnits)
                "partner_id" -> JsonPrimitive(line.partnerId)
                "partner_account" -> JsonPrimitive(line.partnerAccount.identifier)
                "partner_scope" -> JsonPrimitive(line.partnerAccount.scopeIdentity)
                "detail_type" -> JsonPrimitive(line.detail?.type)
                "detail_id" -> JsonPrimitive(line.detail?.id)
                else -> throw ReplayError(".column", "a column of double_entry_lines", "\"$column\"")
            }

        /** `Line.find(line).pair`: `[credit, debit]`. */
        private fun linePair(step: JsonObject): JsonElement {
            val line = stored(step)
            val (first, second) = lines.pairOf(line) ?: throw noPartner(line)
            return JsonArray(listOf(json(first), json(second)))
        }

        /** `DoubleEntry::Line.<scope>.order(:id)`: `credits` (amount above zero) or `debits` (below), U-07. */
        private fun linesScope(step: JsonObject): JsonElement {
            val found =
                when (val scope = step.string("scope")) {
                    "credits" -> lines.credits()
                    "debits" -> lines.debits()
                    else -> throw ReplayError(".scope", "credits or debits", "\"$scope\"")
                }
            return JsonArray(found.map { json(it) })
        }

        /** The stored line of the step's `line` id. */
        private fun stored(step: JsonObject): Line {
            val id = long(step, "line")
            return lines.find(id) ?: throw ReplayError(".line", "the id of a stored line", id.toString())
        }

        /** `Line#partner` of [line]; the gem's `find` raises where the port has no partner. */
        private fun partner(line: Line): Line = lines.partnerOf(line) ?: throw noPartner(line)

        private fun noPartner(line: Line): ReplayError =
            ReplayError(".line", "a line with a stored partner", "line ${line.id}, partner_id ${line.partnerId}")

        /** The step's optional `code`: missing or `null` is no code. */
        private fun code(step: JsonObject): String? =
            step["code"]?.takeUnless { it is JsonNull }?.let { step.string("code") }

        /** A detail `{"invoice": <integer>}`: the recorder's `Invoice` of that id. */
        private fun detail(value: JsonElement): Detail {
            val id = (value as? JsonObject)?.takeIf { it.keys == setOf("invoice") }?.get("invoice") as? JsonPrimitive
            return id?.takeIf { !it.isString }?.longOrNull?.let { Detail(INVOICE, it) }
                ?: throw ReplayError(".detail", "{\"invoice\": <integer>}", value.toString())
        }

        private fun defineTransfer(step: JsonObject): TransferRule =
            config.transfers.define(step.string("from"), step.string("to"), step.string("code"))

        private fun define(step: JsonObject): Account {
            val scope = if ("scope" in step) step.string("scope") else "none"
            val scopeIdentifier =
                when (scope) {
                    "none" -> null
                    else -> scopes[scope] ?: throw ReplayError(".scope", "a known scope", "\"$scope\"")
                }
            return config.accounts.define(
                identifier = step.string("identifier"),
                scopeIdentifier = scopeIdentifier,
                positiveOnly = flag(step, "positive_only"),
                negativeOnly = flag(step, "negative_only"),
                currency = if ("currency" in step) Currency.getInstance(step.string("currency")) else null,
            )
        }

        /** `DoubleEntry.account(identifier, scope:, scope_identity:)` for an account [reference]. */
        private fun account(reference: JsonObject): AccountInstance =
            doubleEntry.account(
                identifier = reference.string("identifier"),
                scope = reference["scope"]?.let(::scope),
                scopeIdentity = if ("scope_identity" in reference) reference.string("scope_identity") else null,
            )

        /** A scope: a string, as is, or `{"user": <integer>}`, a [User] with that id. */
        private fun scope(value: JsonElement): Any {
            if (value is JsonPrimitive && value.isString) return value.content
            val id = (value as? JsonObject)?.takeIf { it.keys == setOf("user") }?.get("user") as? JsonPrimitive
            return id?.takeIf { !it.isString }?.intOrNull?.let(::User)
                ?: throw ReplayError(".scope", "a string or {\"user\": <integer>}", value.toString())
        }

        /** The object at [path] of the step: an account reference. */
        private fun reference(
            value: JsonElement?,
            path: String,
        ): JsonObject = value as? JsonObject ?: throw ReplayError(path, "an object", value?.toString() ?: MISSING)

        private fun long(
            step: JsonObject,
            key: String,
        ): Long {
            val value = step[key]
            return (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                ?: throw ReplayError(".$key", "an integer", value?.toString() ?: MISSING)
        }

        /** An optional boolean argument; not given is `false`. */
        private fun flag(
            step: JsonObject,
            key: String,
        ): Boolean {
            val value = step[key] ?: return false
            return (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: throw ReplayError(".$key", "a boolean", value.toString())
        }

        /** An account as the oracle writes a definition: `{"currency", "identifier", "negative_only", …}`. */
        private fun json(account: Account): JsonElement =
            JsonObject(
                mapOf(
                    "currency" to JsonPrimitive(account.currency.currencyCode),
                    "identifier" to JsonPrimitive(account.identifier),
                    "negative_only" to if (account.negativeOnly) JsonPrimitive(true) else JsonNull,
                    "positive_only" to if (account.positiveOnly) JsonPrimitive(true) else JsonNull,
                    "scoped" to JsonPrimitive(account.isScoped),
                ),
            )

        /** An account instance as the oracle writes one: `{"currency", "identifier", "scope_identity"}`. */
        private fun json(account: AccountInstance): JsonElement =
            JsonObject(
                mapOf(
                    "currency" to JsonPrimitive(account.currency.currencyCode),
                    "identifier" to JsonPrimitive(account.identifier),
                    "scope_identity" to JsonPrimitive(account.scopeIdentity),
                ),
            )

        /** A rule as the oracle writes a `Transfer`: `{"code", "from", "to"}`. */
        private fun json(rule: TransferRule): JsonElement =
            JsonObject(
                mapOf(
                    "code" to JsonPrimitive(rule.code),
                    "from" to JsonPrimitive(rule.from),
                    "to" to JsonPrimitive(rule.to),
                ),
            )

        /** A line as the oracle writes a `Line`: its columns as stored, but `amount` and `balance` as Money. */
        private fun json(line: Line): JsonElement =
            JsonObject(
                mapOf(
                    "account" to JsonPrimitive(line.account.identifier),
                    "amount" to json(line.amount),
                    "balance" to json(line.balance),
                    "code" to JsonPrimitive(line.code),
                    "detail_id" to JsonPrimitive(line.detail?.id),
                    "detail_type" to JsonPrimitive(line.detail?.type),
                    "id" to JsonPrimitive(line.id),
                    "partner_account" to JsonPrimitive(line.partnerAccount.identifier),
                    "partner_id" to JsonPrimitive(line.partnerId),
                    "partner_scope" to JsonPrimitive(line.partnerAccount.scopeIdentity),
                    "scope" to JsonPrimitive(line.account.scopeIdentity),
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
