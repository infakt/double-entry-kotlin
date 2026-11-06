package pl.infakt.doubleentry.parity

import java.sql.SQLException
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
import pl.infakt.doubleentry.DoubleEntry
import pl.infakt.doubleentry.DoubleEntryConfiguration
import pl.infakt.doubleentry.Line
import pl.infakt.doubleentry.LockScope
import pl.infakt.doubleentry.Money
import pl.infakt.doubleentry.TransferRule
import pl.infakt.doubleentry.schema.DoubleEntrySchema

/**
 * The nested-locking area: `lock_accounts` with `transfer`, nested `lock_accounts`, `rollback` and `raise` inside,
 * `in_transaction`, `balance` and `inject_error`, on a new PostgreSQL database per scenario with the generator shape
 * for `input.schema`, and the clock frozen at `input.clock` (oracle/README.md, "Ops" and "Locking extensions"). The
 * scenarios define their accounts and transfer rules first, so the area also runs `define_account` (with the scopes
 * `none` and `identity`) and `define_transfer`, and the configuration area's ops, on the same configuration. The
 * effects are the rows of the four tables ([ledgerTables]).
 *
 * `inject_error` takes the target `with_restart_on_deadlock` and the error `ActiveRecord::StatementInvalid` only: the
 * port raises it through the seam `Locking.beforeGrabLocks`, in the lock's transaction before the balance records are
 * read, as a `SQLException` with the step's message. The target `create!` and `ActiveRecord::RestartTransaction` belong
 * to the locking-extensions area.
 */
object NestedLockingExecutor : AreaExecutor {
    /** The clock of a scenario without `input.clock` (oracle/README.md, "Adding a scenario"). */
    private const val DEFAULT_CLOCK = "2024-01-01T00:00:00Z"

    /** The one `inject_error` target of this area: `AccountBalance.with_restart_on_deadlock` (locking.rb:130). */
    private const val WITH_RESTART_ON_DEADLOCK = "with_restart_on_deadlock"

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

        /**
         * The blocks running, innermost last: the receiver of a `lock_accounts` block, or `null` for an
         * `in_transaction` block, where the port has no rollback.
         */
        private val blocks = ArrayDeque<LockScope?>()

        /** The messages of the errors `inject_error` arranged and the lock has not raised yet, in order. */
        private val injected = ArrayDeque<String>()

        init {
            doubleEntry.locking.beforeGrabLocks = {
                injected.removeFirstOrNull()?.let { throw SQLException(it) }
            }
        }

        override fun run(step: JsonObject): JsonElement =
            when (step.string("op")) {
                "define_account" -> json(define(step))
                "define_transfer" -> json(defineTransfer(step))
                "lock_accounts" -> lockAccounts(step)
                "in_transaction" -> inTransaction(step)
                "transfer" -> transfer(step)
                "balance" -> json(balance(step))
                "rollback" -> rollback()
                "raise" -> throw RuntimeException(step.string("message"))
                "inject_error" -> injectError(step)
                else -> configuration.run(step)
            }

        override fun tables(): Map<String, JsonElement> = ledgerTables(database, jsonMetadata)

        /** `DoubleEntry.lock_accounts(*accounts) { <steps> }`; the nested steps' values are not kept. */
        private fun lockAccounts(step: JsonObject): JsonElement {
            val accounts =
                array(step, "accounts").mapIndexed { index, each -> account(reference(each, ".accounts[$index]")) }
            doubleEntry.lockAccounts(*accounts.toTypedArray()) { nested(step, this) }
            return JsonNull
        }

        /** `AccountBalance.transaction { <steps> }`: a regular transaction of the database, in one attempt. */
        private fun inTransaction(step: JsonObject): JsonElement {
            transaction(database) {
                maxAttempts = 1
                nested(step, null)
            }
            return JsonNull
        }

        /** Runs the nested steps of [step] in the block whose receiver is [scope]. */
        private fun nested(
            step: JsonObject,
            scope: LockScope?,
        ) {
            blocks.addLast(scope)
            try {
                array(step, "steps").forEachIndexed { index, each -> run(reference(each, ".steps[$index]")) }
            } finally {
                blocks.removeLast()
            }
        }

        /** `raise ActiveRecord::Rollback`: [LockScope.rollback] of the innermost block, a `lock_accounts` one. */
        private fun rollback(): Nothing {
            val scope =
                blocks.lastOrNull()
                    ?: throw ReplayError(".op", "rollback inside a lock_accounts block", "\"rollback\" outside it")
            scope.rollback()
        }

        /**
         * Arranges `times` errors with the step's `message` for the next locks, each raised in the lock's transaction
         * before the balance records are read, where the gem's `with_restart_on_deadlock` runs its block.
         */
        private fun injectError(step: JsonObject): JsonElement {
            val target = step.string("target")
            if (target != WITH_RESTART_ON_DEADLOCK) {
                throw ReplayError(".target", "\"$WITH_RESTART_ON_DEADLOCK\"", "\"$target\"")
            }
            if ("error" in step && step.string("error") != ErrorNames.STATEMENT_INVALID) {
                throw ReplayError(".error", "\"${ErrorNames.STATEMENT_INVALID}\"", step["error"].toString())
            }
            val times = step["times"]
            val count =
                (times as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 }
                    ?: throw ReplayError(".times", "a positive integer", times?.toString() ?: MISSING)
            val message = step.string("message")
            repeat(count) { injected.addLast(message) }
            return JsonNull
        }

        /**
         * `DoubleEntry.transfer(Money.new(amount), from:, to:, code:)`, the amount in the default currency; the value
         * is `[credit, debit]`. The area's scenarios give no `detail` and no `currency`.
         */
        private fun transfer(step: JsonObject): JsonElement {
            listOf("detail", "currency").firstOrNull { it in step }?.let {
                throw ReplayError(".$it", "no $it in the nested-locking area", step[it].toString())
            }
            val amount = Money(long(step, "amount"), config.defaultCurrency)
            val from = account(reference(step["from"], ".from"))
            val to = account(reference(step["to"], ".to"))
            val code = if ("code" in step) step.string("code") else null
            val (credit, debit) = doubleEntry.transfer(amount, from, to, code)
            return JsonArray(listOf(json(credit), json(debit)))
        }

        /** `DoubleEntry.account(...).balance` of the step's `account`, without options, all the scenarios ask. */
        private fun balance(step: JsonObject): Money {
            if (step.keys != setOf("op", "account")) {
                throw ReplayError("", "a balance of an account without options", step.toString())
            }
            return doubleEntry.balance(account(reference(step["account"], ".account")))
        }

        private fun defineTransfer(step: JsonObject): TransferRule =
            config.transfers.define(step.string("from"), step.string("to"), step.string("code"))

        private fun define(step: JsonObject): Account {
            val scope = if ("scope" in step) step.string("scope") else "none"
            val scopeIdentifier =
                when (scope) {
                    "none" -> null
                    else -> scopes[scope] ?: throw ReplayError(".scope", "a scope of this area", "\"$scope\"")
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
