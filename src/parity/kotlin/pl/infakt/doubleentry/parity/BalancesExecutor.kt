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
import pl.infakt.doubleentry.DoubleEntry
import pl.infakt.doubleentry.DoubleEntryConfiguration
import pl.infakt.doubleentry.Line
import pl.infakt.doubleentry.Money
import pl.infakt.doubleentry.TransferRule
import pl.infakt.doubleentry.schema.DoubleEntrySchema

/**
 * The balances area: `travel` and `balance` on a new PostgreSQL database per scenario with the generator shape for
 * `input.schema`, and the clock frozen at `input.clock` until a `travel` moves it (oracle/README.md, "Ops"). The
 * scenarios define their accounts and transfer rules and transfer first, and read the running balance of a line, so
 * the area also runs `define_account` (with the scopes `none`, `identity`, `user_id` and `user_only`),
 * `define_transfer`, `transfer` (with its optional `currency`) and `line_attr` with an `attr`, and the configuration
 * area's ops, on the same configuration. The effects are the rows of the four tables ([ledgerTables]).
 */
object BalancesExecutor : AreaExecutor {
    /** The clock of a scenario without `input.clock` (oracle/README.md, "Adding a scenario"). */
    private const val DEFAULT_CLOCK = "2024-01-01T00:00:00Z"

    override fun start(input: JsonObject): Run {
        val schema = input["schema"] as? JsonObject
        val shape = (schema?.get("shape") as? JsonPrimitive)?.content ?: "generator"
        require(shape == "generator") { "input.schema: the port creates the generator shape only, got $shape" }
        val jsonMetadata = (schema?.get("json_metadata") as? JsonPrimitive)?.booleanOrNull ?: false
        val start = Instant.parse((input["clock"] as? JsonPrimitive)?.content ?: DEFAULT_CLOCK)
        val database = TestDatabases.empty()
        DoubleEntrySchema.create(database, jsonMetadata)
        return Run(database, jsonMetadata, start)
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

    /** A scenario of the balances area; the account-balances area runs its ops on one ([AccountBalancesExecutor]). */
    class Run internal constructor(
        private val database: Database,
        private val jsonMetadata: Boolean,
        start: Instant,
    ) : ScenarioRun {
        /** The frozen time: `input.clock`, then the `to` of the last `travel`. */
        private var now: Instant = start

        private val config =
            DoubleEntryConfiguration().apply {
                clock =
                    object : Clock {
                        override fun now(): Instant = this@Run.now
                    }
            }

        private val configuration = ConfigurationExecutor.start(config)

        internal val doubleEntry = DoubleEntry(database, config)

        override fun run(step: JsonObject): JsonElement =
            when (step.string("op")) {
                "define_account" -> json(define(step))
                "define_transfer" -> json(defineTransfer(step))
                "transfer" -> transfer(step)
                "travel" -> travel(step)
                "balance" -> json(balance(step))
                "line_attr" -> lineAttr(step)
                else -> configuration.run(step)
            }

        override fun tables(): Map<String, JsonElement> = ledgerTables(database, jsonMetadata)

        /** `Timecop.freeze(Time.iso8601(to))` for the steps after it; the value is `null`. */
        private fun travel(step: JsonObject): JsonElement {
            now = time(step, "to")
            return JsonNull
        }

        /**
         * `DoubleEntry.account(...).balance(...)` for the step's `account`, or `DoubleEntry.balance(identifier,
         * scope:, ...)` for its `identifier` and optional `scope`, exactly one of the two; only the options the step
         * gives are passed.
         */
        private fun balance(step: JsonObject): Money {
            val from = if ("from" in step) time(step, "from") else null
            val to = if ("to" in step) time(step, "to") else null
            val at = if ("at" in step) time(step, "at") else null
            val code = if ("code" in step) step.string("code") else null
            val codes = if ("codes" in step) strings(step, "codes") else emptyList()
            return when {
                "account" in step && "identifier" !in step -> {
                    val account = account(reference(step["account"], ".account"))
                    account.balance(from = from, to = to, at = at, code = code, codes = codes)
                }

                "identifier" in step && "account" !in step -> {
                    doubleEntry.balance(
                        identifier = step.string("identifier"),
                        scope = step["scope"]?.let(::scope),
                        from = from,
                        to = to,
                        at = at,
                        code = code,
                        codes = codes,
                    )
                }

                else -> throw ReplayError(".account", "exactly one of account and identifier", step.toString())
            }
        }

        /**
         * `DoubleEntry.transfer(Money.new(amount, currency), from:, to:, code:)`, the amount in the step's `currency`
         * or else the default currency; the value is `[credit, debit]`.
         */
        private fun transfer(step: JsonObject): JsonElement {
            val currency = if ("currency" in step) currency(step.string("currency")) else config.defaultCurrency
            val amount = Money(long(step, "amount"), currency)
            val from = account(reference(step["from"], ".from"))
            val to = account(reference(step["to"], ".to"))
            val code = if ("code" in step) step.string("code") else null
            if ("detail" in step) throw ReplayError(".detail", "no detail in the balances area", step.toString())
            val (credit, debit) = doubleEntry.transfer(amount, from, to, code)
            return JsonArray(listOf(json(credit), json(debit)))
        }

        /** `Line.find(line).<attr>`, one of Line's readers; the balances area reads no raw column. */
        private fun lineAttr(step: JsonObject): JsonElement {
            if ("column" in step) throw ReplayError(".column", "an attr in the balances area", step.toString())
            val id = long(step, "line")
            val line =
                doubleEntry.lines.find(id) ?: throw ReplayError(".line", "the id of a stored line", id.toString())
            return when (val attr = step.string("attr")) {
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
                currency = if ("currency" in step) currency(step.string("currency")) else null,
            )
        }

        /** `DoubleEntry.account(identifier, scope:, scope_identity:)` for an account [reference]. */
        internal fun account(reference: JsonObject): AccountInstance =
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

        private fun currency(code: String): Currency =
            runCatching { Currency.getInstance(code) }.getOrNull()
                ?: throw ReplayError(".currency", "an ISO 4217 currency code", "\"$code\"")

        /** The object at [path] of the step: an account reference. */
        private fun reference(
            value: JsonElement?,
            path: String,
        ): JsonObject = value as? JsonObject ?: throw ReplayError(path, "an object", value?.toString() ?: MISSING)

        /** An absolute ISO 8601 time of the step (oracle/README.md, "The balance ops"). */
        private fun time(
            step: JsonObject,
            key: String,
        ): Instant {
            val text = step.string(key)
            return runCatching { Instant.parse(text) }.getOrNull()
                ?: throw ReplayError(".$key", "an ISO 8601 time", "\"$text\"")
        }

        /** An array of strings of the step, such as `codes`. */
        private fun strings(
            step: JsonObject,
            key: String,
        ): List<String> {
            val value = step[key]
            val items = value as? JsonArray ?: throw ReplayError(".$key", "an array", value?.toString() ?: MISSING)
            return items.mapIndexed { index, item ->
                (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw ReplayError(".$key[$index]", "a string", item.toString())
            }
        }

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
