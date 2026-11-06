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
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import pl.infakt.doubleentry.Account
import pl.infakt.doubleentry.AccountBalance
import pl.infakt.doubleentry.AccountBalances
import pl.infakt.doubleentry.AccountInstance
import pl.infakt.doubleentry.DoubleEntry
import pl.infakt.doubleentry.DoubleEntryConfiguration
import pl.infakt.doubleentry.Money
import pl.infakt.doubleentry.TransferRule
import pl.infakt.doubleentry.schema.DoubleEntrySchema
import pl.infakt.doubleentry.schema.LinesTable

/**
 * The locking area: `lock_accounts`, `in_transaction`, `seed_line` and `account_balance` on a new PostgreSQL database
 * per scenario with the generator shape for `input.schema`, and the clock frozen at `input.clock` (oracle/README.md,
 * "Ops"). The scenarios define their accounts and transfer rules first, so the area also runs `define_account` (with
 * the scopes `none` and `identity`) and `define_transfer`, and the configuration area's ops, on the same
 * configuration. The effects are the rows of the four tables ([ledgerTables]).
 */
object LockingExecutor : AreaExecutor {
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

    /** The scope identifiers `define_account` names that this area's scenarios use. */
    private val scopes: Map<String, (Any) -> Any?> = mapOf("identity" to { value: Any -> value })

    private class Run(
        private val database: Database,
        private val config: DoubleEntryConfiguration,
        private val jsonMetadata: Boolean,
    ) : ScenarioRun {
        private val configuration = ConfigurationExecutor.start(config)

        private val doubleEntry = DoubleEntry(database, config)

        private val balances = AccountBalances(database, config)

        override fun run(step: JsonObject): JsonElement =
            when (step.string("op")) {
                "define_account" -> json(define(step))
                "define_transfer" -> json(defineTransfer(step))
                "seed_line" -> seedLine(step)
                "lock_accounts" -> lockAccounts(step)
                "in_transaction" -> inTransaction(step)
                "account_balance" -> balances.findByAccount(account(step))?.let { json(it) } ?: JsonNull
                else -> configuration.run(step)
            }

        override fun tables(): Map<String, JsonElement> = ledgerTables(database, jsonMetadata)

        /** `DoubleEntry.lock_accounts(*accounts) { <steps> }`; the nested steps' values are not kept. */
        private fun lockAccounts(step: JsonObject): JsonElement {
            val accounts =
                array(step, "accounts").mapIndexed { index, each -> account(reference(each, ".accounts[$index]")) }
            doubleEntry.lockAccounts(*accounts.toTypedArray()) { nested(step) }
            return JsonNull
        }

        /** `AccountBalance.transaction { <steps> }`: a regular transaction of the database, in one attempt. */
        private fun inTransaction(step: JsonObject): JsonElement {
            transaction(database) {
                maxAttempts = 1
                nested(step)
            }
            return JsonNull
        }

        private fun nested(step: JsonObject) {
            array(step, "steps").forEachIndexed { index, each -> run(reference(each, ".steps[$index]")) }
        }

        /**
         * `DoubleEntry::Line.create!` with the step's account, partner account, amount, balance and code, the
         * timestamps the clock's; the value is the `Line`. The step must give the code.
         */
        private fun seedLine(step: JsonObject): JsonElement {
            val account = account(reference(step["account"], ".account"))
            val partner = account(reference(step["partner_account"], ".partner_account"))
            val amount = long(step, "amount")
            val balance = long(step, "balance")
            val code = step.string("code")
            val lines = LinesTable.of(jsonMetadata)
            val now = config.clock.now()
            val id =
                transaction(database) {
                    maxAttempts = 1
                    lines
                        .insertAndGetId { row ->
                            row[lines.account] = account.identifier
                            row[lines.scope] = account.scopeIdentity
                            row[lines.code] = code
                            row[lines.amount] = amount
                            row[lines.balance] = balance
                            row[lines.partnerAccount] = partner.identifier
                            row[lines.partnerScope] = partner.scopeIdentity
                            row[lines.createdAt] = now
                            row[lines.updatedAt] = now
                        }.value
                }
            return JsonObject(
                mapOf(
                    "account" to JsonPrimitive(account.identifier),
                    "amount" to json(Money(amount, account.currency)),
                    "balance" to json(Money(balance, account.currency)),
                    "code" to JsonPrimitive(code),
                    "detail_id" to JsonNull,
                    "detail_type" to JsonNull,
                    "id" to JsonPrimitive(id),
                    "partner_account" to JsonPrimitive(partner.identifier),
                    "partner_id" to JsonNull,
                    "partner_scope" to JsonPrimitive(partner.scopeIdentity),
                    "scope" to JsonPrimitive(account.scopeIdentity),
                ),
            )
        }

        private fun defineTransfer(step: JsonObject): TransferRule =
            config.transfers.define(step.string("from"), step.string("to"), step.string("code"))

        private fun define(step: JsonObject): Account {
            val scope = if ("scope" in step) step.string("scope") else "none"
            val scopeIdentifier =
                when (scope) {
                    "none" -> null
                    else -> scopes[scope] ?: throw ReplayError(".scope", "a scope of the locking area", "\"$scope\"")
                }
            return config.accounts.define(
                identifier = step.string("identifier"),
                scopeIdentifier = scopeIdentifier,
                positiveOnly = flag(step, "positive_only"),
                negativeOnly = flag(step, "negative_only"),
                currency = if ("currency" in step) Currency.getInstance(step.string("currency")) else null,
            )
        }

        /** `DoubleEntry.account(identifier, scope:, scope_identity:)` for an account reference with a string scope. */
        private fun account(reference: JsonObject): AccountInstance =
            doubleEntry.account(
                identifier = reference.string("identifier"),
                scope = if ("scope" in reference) reference.string("scope") else null,
                scopeIdentity = if ("scope_identity" in reference) reference.string("scope_identity") else null,
            )

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

        /** A balance record as the oracle writes an `AccountBalance`: `{"account", "balance", "id", "scope"}`. */
        private fun json(balance: AccountBalance): JsonElement =
            JsonObject(
                mapOf(
                    "account" to JsonPrimitive(balance.account),
                    "balance" to json(balance.balance),
                    "id" to JsonPrimitive(balance.id),
                    "scope" to JsonPrimitive(balance.scope),
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
