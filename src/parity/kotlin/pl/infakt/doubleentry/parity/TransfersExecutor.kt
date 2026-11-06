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
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
 * The transfers area: `transfer` and `in_transaction` on a new PostgreSQL database per scenario with the generator
 * shape for `input.schema`, and the clock frozen at `input.clock` (oracle/README.md, "Ops"). The scenarios define their
 * accounts and transfer rules first, so the area also runs `define_account` (with the scopes `none`, `identity`,
 * `user_id` and `user_only`) and `define_transfer`, and the configuration area's ops, on the same configuration. The
 * effects are the rows of the four tables ([ledgerTables]).
 */
object TransfersExecutor : AreaExecutor {
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

        override fun run(step: JsonObject): JsonElement =
            when (step.string("op")) {
                "define_account" -> json(define(step))
                "define_transfer" -> json(defineTransfer(step))
                "transfer" -> transfer(step)
                "in_transaction" -> inTransaction(step)
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

        /** `AccountBalance.transaction { <steps> }`: a regular transaction of the database, in one attempt. */
        private fun inTransaction(step: JsonObject): JsonElement {
            transaction(database) {
                maxAttempts = 1
                array(step, "steps").forEachIndexed { index, each -> run(reference(each, ".steps[$index]")) }
            }
            return JsonNull
        }

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

        /** The object at [path] of the step: an account reference or a nested step. */
        private fun reference(
            value: JsonElement?,
            path: String,
        ): JsonObject = value as? JsonObject ?: throw ReplayError(path, "an object", value?.toString() ?: MISSING)

        private fun array(
            step: JsonObject,
            key: String,
        ): JsonArray =
            step[key] as? JsonArray ?: throw ReplayError(".$key", "an array", step[key]?.toString() ?: MISSING)

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
