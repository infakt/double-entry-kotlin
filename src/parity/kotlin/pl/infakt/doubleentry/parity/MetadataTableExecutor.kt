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
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import pl.infakt.doubleentry.Account
import pl.infakt.doubleentry.AccountInstance
import pl.infakt.doubleentry.Detail
import pl.infakt.doubleentry.DoubleEntry
import pl.infakt.doubleentry.DoubleEntryConfiguration
import pl.infakt.doubleentry.Line
import pl.infakt.doubleentry.Money
import pl.infakt.doubleentry.TransferRule
import pl.infakt.doubleentry.schema.AccountBalancesTable
import pl.infakt.doubleentry.schema.DoubleEntrySchema
import pl.infakt.doubleentry.schema.LineChecksTable
import pl.infakt.doubleentry.schema.LineMetadataTable
import pl.infakt.doubleentry.schema.LinesTable

/**
 * The metadata-table area: `transfer` with `metadata`, `line_metadata_count` and `line_metadata_of` on a new
 * PostgreSQL database per scenario, with the clock frozen at `input.clock` (oracle/README.md, "Ops" and "Metadata").
 * The scenarios define their accounts and transfer rules first, so the area also runs `define_account` (with the
 * scopes `none`, `identity`, `user_id` and `user_only`) and `define_transfer`, and the configuration area's ops, on
 * the same configuration.
 *
 * `input.schema` is the spec shape (spec/support/schema.rb), whose lines have the `metadata` column and which has
 * `double_entry_line_metadata` too, or the generator shape for its `json_metadata`. The harness cannot see the tests'
 * `TestDatabase.specLedger`, so it builds the spec shape itself, the same way: the generator's tables with json
 * metadata, plus the line metadata table. The effects are the rows of the four tables of the shape, the lines with
 * their `metadata` column on the spec shape.
 */
object MetadataTableExecutor : AreaExecutor {
    /** The clock of a scenario without `input.clock` (oracle/README.md, "Adding a scenario"). */
    private const val DEFAULT_CLOCK = "2024-01-01T00:00:00Z"

    /** The tables of the spec shape: the lines with the `metadata` column, and `double_entry_line_metadata`. */
    private val specTables: List<LongIdTable> =
        listOf(AccountBalancesTable, LinesTable.withJsonMetadata, LineChecksTable, LineMetadataTable)

    override fun start(input: JsonObject): ScenarioRun {
        val schema = input["schema"] as? JsonObject
        val shape = (schema?.get("shape") as? JsonPrimitive)?.content ?: "generator"
        require(shape == "generator" || shape == "spec") {
            "input.schema: the port creates the generator and the spec shapes only, got $shape"
        }
        val jsonMetadata = (schema?.get("json_metadata") as? JsonPrimitive)?.booleanOrNull ?: false
        val frozen = Instant.parse((input["clock"] as? JsonPrimitive)?.content ?: DEFAULT_CLOCK)
        val database = TestDatabases.empty()
        val spec = shape == "spec"
        if (spec) {
            DoubleEntrySchema.create(database, jsonMetadata = true)
            transaction(database) { SchemaUtils.create(LineMetadataTable) }
        } else {
            DoubleEntrySchema.create(database, jsonMetadata)
        }
        val config =
            DoubleEntryConfiguration().apply {
                clock =
                    object : Clock {
                        override fun now(): Instant = frozen
                    }
            }
        return Run(database, config, spec, jsonMetadata)
    }

    /** The rows of the spec shape's four tables, by name, as [ledgerTables] writes them. */
    private fun specRows(database: Database): Map<String, JsonElement> =
        transaction(database) {
            maxAttempts = 1
            val tables = specTables.associateBy { it.tableName }
            SchemaExecutor.tableNames.associateWith { name -> rows(tables.getValue(name)) }
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

    /** What a `transfer`'s `metadata` must be (oracle/README.md, "Metadata"). */
    private const val METADATA = "{\"<key>\": \"<value>\" | [\"<value>\", ...]}"

    private class Run(
        private val database: Database,
        private val config: DoubleEntryConfiguration,
        private val spec: Boolean,
        private val jsonMetadata: Boolean,
    ) : ScenarioRun {
        private val configuration = ConfigurationExecutor.start(config)

        private val doubleEntry = DoubleEntry(database, config)

        override fun run(step: JsonObject): JsonElement =
            when (step.string("op")) {
                "define_account" -> json(define(step))
                "define_transfer" -> json(defineTransfer(step))
                "transfer" -> transfer(step)
                "line_metadata_count" -> JsonPrimitive(metadataCount())
                "line_metadata_of" -> metadataOf(step)
                else -> configuration.run(step)
            }

        override fun tables(): Map<String, JsonElement> =
            if (spec) specRows(database) else ledgerTables(database, jsonMetadata)

        /**
         * `DoubleEntry.transfer(Money.new(amount, currency), from:, to:, code:, detail:, metadata:)`, the amount in the
         * step's `currency` or the default one; the value is `[credit, debit]`. The metadata's keys are given sorted,
         * as the recorder gives them (oracle/README.md, "Metadata").
         */
        private fun transfer(step: JsonObject): JsonElement {
            val currency = if ("currency" in step) Currency.getInstance(step.string("currency")) else null
            val amount = Money(long(step, "amount"), currency ?: config.defaultCurrency)
            val from = account(reference(step["from"], ".from"))
            val to = account(reference(step["to"], ".to"))
            val code = if ("code" in step) step.string("code") else null
            val detail = step["detail"]?.let(::detail)
            val metadata = step["metadata"]?.let(::metadata)
            val (credit, debit) = doubleEntry.transfer(amount, from, to, code, detail, metadata)
            return JsonArray(listOf(json(credit), json(debit)))
        }

        /** `DoubleEntry::LineMetadata.count`. */
        private fun metadataCount(): Long = transaction(database) { LineMetadataTable.selectAll().count() }

        /**
         * `Line.find(line).metadata`: the line's metadata as the port reads it, a JSON object or `null`. Without json
         * metadata it is `null`, also on the generator's tables, where the gem raises `NoMethodError` (X-04).
         */
        private fun metadataOf(step: JsonObject): JsonElement {
            val id = long(step, "line")
            val line =
                doubleEntry.lines.find(id) ?: throw ReplayError(".line", "the id of a stored line", id.toString())
            return line.metadata?.let { metadata -> json(metadata) } ?: JsonNull
        }

        /** A metadata `{"<key>": "<value>" | ["<value>", ...]}`, its keys sorted. */
        private fun metadata(value: JsonElement): Map<String, Any> {
            val pairs = value as? JsonObject ?: throw ReplayError(".metadata", METADATA, value.toString())
            return pairs.keys.sorted().associateWith { key ->
                val each = pairs.getValue(key)
                val wrong = { ReplayError(".metadata.$key", METADATA, each.toString()) }
                text(each) ?: (each as? JsonArray)?.map { text(it) ?: throw wrong() } ?: throw wrong()
            }
        }

        /** A JSON string's content, or `null` for any other value. */
        private fun text(value: JsonElement): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

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

        /** A line's metadata as the oracle writes a json metadata: an object of its values. */
        private fun json(metadata: Map<String, Any?>): JsonElement =
            JsonObject(metadata.mapValues { (_, each) -> element(each) })

        /** A value of [Line.metadata] as JSON. A type the reader does not give is a harness error. */
        private fun element(each: Any?): JsonElement =
            when (each) {
                null -> JsonNull
                is String -> JsonPrimitive(each)
                is Number -> JsonPrimitive(each)
                is Boolean -> JsonPrimitive(each)
                is List<*> -> JsonArray(each.map { element(it) })
                is Map<*, *> -> JsonObject(each.entries.associate { entry -> "${entry.key}" to element(entry.value) })
                else -> error("no oracle value for a metadata value of ${each::class}")
            }
    }
}
