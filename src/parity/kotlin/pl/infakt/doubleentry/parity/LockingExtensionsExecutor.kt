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
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import pl.infakt.doubleentry.Account
import pl.infakt.doubleentry.AccountInstance
import pl.infakt.doubleentry.DoubleEntry
import pl.infakt.doubleentry.DoubleEntryConfiguration
import pl.infakt.doubleentry.DoubleEntryEvents
import pl.infakt.doubleentry.LockingExtensions
import pl.infakt.doubleentry.RestartTransaction
import pl.infakt.doubleentry.TransferRule
import pl.infakt.doubleentry.schema.DoubleEntrySchema

/**
 * The locking-extensions area: the gem's `ActiveRecord::LockingExtensions` through `restartable_transaction`,
 * `create_ignoring_duplicates` and `lock_accounts`, with errors arranged by `inject_error`, on a new PostgreSQL
 * database per scenario and the clock frozen at `input.clock` (oracle/README.md, "Locking extensions"). The scenarios
 * that lock define their accounts and transfer rules first, so the area also runs `define_account` (with the scopes
 * `none` and `identity`) and `define_transfer`, and the configuration area's ops, on the same configuration.
 *
 * The effects are the events published, `[{"exception_class", "message", "name"}]` with the class from [ErrorNames];
 * the rows of the four tables ([ledgerTables]) with `input.schema`, which only the generator shape may be; and the
 * rows of `users` once a step used it.
 *
 * `inject_error` arranges an `ActiveRecord::StatementInvalid`, a `SQLException` with the step's message, or an
 * `ActiveRecord::RestartTransaction`. At `with_restart_on_deadlock` the lock raises it through the seam
 * `Locking.beforeGrabLocks`, inside its `withRestartOnDeadlock`; at `create!` the creation of a user raises it before
 * the insert.
 */
object LockingExtensionsExecutor : AreaExecutor {
    /** The clock of a scenario without `input.clock` (oracle/README.md, "Adding a scenario"). */
    private const val DEFAULT_CLOCK = "2024-01-01T00:00:00Z"

    /** `AccountBalance.with_restart_on_deadlock` (locking.rb:130), a target of `inject_error`. */
    private const val WITH_RESTART_ON_DEADLOCK = "with_restart_on_deadlock"

    /** `UserRecord.create!`, a target of `inject_error`. */
    private const val CREATE = "create!"

    /** The gem name of [RestartTransaction], an error `inject_error` may arrange. */
    private const val RESTART_TRANSACTION = "ActiveRecord::RestartTransaction"

    override fun start(input: JsonObject): ScenarioRun {
        val schema = input["schema"] as? JsonObject
        val shape = (schema?.get("shape") as? JsonPrimitive)?.content ?: "generator"
        require(shape == "generator") { "input.schema: the port creates the generator shape only, got $shape" }
        val jsonMetadata = (schema?.get("json_metadata") as? JsonPrimitive)?.booleanOrNull ?: false
        val frozen = Instant.parse((input["clock"] as? JsonPrimitive)?.content ?: DEFAULT_CLOCK)
        val database = TestDatabases.empty()
        if (schema != null) DoubleEntrySchema.create(database, jsonMetadata)
        val config =
            DoubleEntryConfiguration().apply {
                clock =
                    object : Clock {
                        override fun now(): Instant = frozen
                    }
            }
        return Run(database, config, jsonMetadata.takeIf { schema != null })
    }

    /** The scope identifiers `define_account` names that this area's scenarios use. */
    private val scopes: Map<String, (Any) -> Any?> = mapOf("identity" to { value: Any -> value })

    /** The recorder's `users` (oracle/README.md, "Locking extensions"): spec/support/schema.rb:58-63's shape. */
    private object UsersTable : LongIdTable("users") {
        private const val STRING_LENGTH = 255

        val username = varchar("username", STRING_LENGTH).uniqueIndex("index_users_on_username")
        val createdAt = timestamp("created_at")
        val updatedAt = timestamp("updated_at")
    }

    /**
     * One scenario on [database]. [jsonMetadata] is the shape's flag with `input.schema`, or `null` without one, when
     * the four tables are not effects.
     */
    private class Run(
        private val database: Database,
        private val config: DoubleEntryConfiguration,
        private val jsonMetadata: Boolean?,
    ) : ScenarioRun {
        private val configuration = ConfigurationExecutor.start(config)

        private val doubleEntry = DoubleEntry(database, config)

        private val extensions = LockingExtensions(config)

        /** The events published, as the oracle writes them, in order. */
        private val events = mutableListOf<JsonElement>()

        /** The errors arranged for the lock's `with_restart_on_deadlock` and not raised yet, in order. */
        private val lockErrors = ArrayDeque<() -> Exception>()

        /** The errors arranged for `create!` and not raised yet, in order. */
        private val createErrors = ArrayDeque<() -> Exception>()

        /** Whether a step used `users`, which is created then and is an effect from then on. */
        private var usersUsed = false

        init {
            config.events =
                object : DoubleEntryEvents {
                    override fun deadlockRestart(exception: Throwable) = publish("deadlock_restart", exception)

                    override fun deadlockRetry(exception: Throwable) = publish("deadlock_retry", exception)

                    override fun duplicateIgnored(exception: Throwable) = publish("duplicate_ignore", exception)
                }
            doubleEntry.locking.beforeGrabLocks = {
                lockErrors.removeFirstOrNull()?.let { throw it() }
            }
        }

        override fun run(step: JsonObject): JsonElement =
            when (step.string("op")) {
                "define_account" -> json(define(step))
                "define_transfer" -> json(defineTransfer(step))
                "lock_accounts" -> lockAccounts(step)
                "inject_error" -> injectError(step)
                "restartable_transaction" -> restartableTransaction(step)
                "create_ignoring_duplicates" -> createIgnoringDuplicates(step)
                else -> configuration.run(step)
            }

        override fun tables(): Map<String, JsonElement> {
            val ledger = jsonMetadata?.let { ledgerTables(database, it) }.orEmpty()
            val users =
                if (usersUsed) {
                    mapOf(
                        "users" to
                            transaction(database) {
                                maxAttempts = 1
                                rows(UsersTable)
                            },
                    )
                } else {
                    emptyMap()
                }
            return ledger + users + mapOf("events" to JsonArray(events))
        }

        /** An event as the oracle writes it: the full name, and the gem class and message of its exception. */
        private fun publish(
            name: String,
            exception: Throwable,
        ) {
            val error = errorOf(exception)
            events +=
                JsonObject(
                    mapOf(
                        "exception_class" to error.getValue("class"),
                        "message" to error.getValue("message"),
                        "name" to JsonPrimitive("$name.double_entry"),
                    ),
                )
        }

        /** `DoubleEntry.lock_accounts(*accounts) { <steps> }`; the nested steps' values are not kept. */
        private fun lockAccounts(step: JsonObject): JsonElement {
            val accounts =
                array(step, "accounts").mapIndexed { index, each -> account(reference(each, ".accounts[$index]")) }
            doubleEntry.lockAccounts(*accounts.toTypedArray()) { nested(step) }
            return JsonNull
        }

        /** `UserRecord.restartable_transaction { <steps> }` (locking_extensions_spec.rb:14). */
        private fun restartableTransaction(step: JsonObject): JsonElement {
            useUsers()
            extensions.restartableTransaction(database) { nested(step) }
            return JsonNull
        }

        /** `UserRecord.create_ignoring_duplicates!(username:)`; its value is `null` (oracle/README.md). */
        private fun createIgnoringDuplicates(step: JsonObject): JsonElement {
            useUsers()
            val username = step.string("username")
            extensions.createIgnoringDuplicates { createUser(username) }
            return JsonNull
        }

        /** `UserRecord.create!(username:)`: an error arranged for `create!` first, otherwise the insert. */
        private fun createUser(username: String) {
            createErrors.removeFirstOrNull()?.let { throw it() }
            transaction(database) {
                maxAttempts = 1
                val now = config.clock.now()
                UsersTable.insert { row ->
                    row[UsersTable.username] = username
                    row[UsersTable.createdAt] = now
                    row[UsersTable.updatedAt] = now
                }
            }
        }

        /** Creates `users` on its first use, as the recorder adds it. */
        private fun useUsers() {
            if (usersUsed) return
            transaction(database) {
                maxAttempts = 1
                SchemaUtils.create(UsersTable)
            }
            usersUsed = true
        }

        /** Runs the nested steps of [step]; their values are not kept. */
        private fun nested(step: JsonObject) {
            array(step, "steps").forEachIndexed { index, each -> run(reference(each, ".steps[$index]")) }
        }

        /** Arranges `times` errors of the step's `error` with its `message` for the next calls to its `target`. */
        private fun injectError(step: JsonObject): JsonElement {
            val queue =
                when (val target = step.string("target")) {
                    WITH_RESTART_ON_DEADLOCK -> lockErrors
                    CREATE -> createErrors
                    else -> throw ReplayError(".target", "\"$WITH_RESTART_ON_DEADLOCK\" or \"$CREATE\"", "\"$target\"")
                }
            val message = step.string("message")
            val error: () -> Exception =
                when (val name = if ("error" in step) step.string("error") else ErrorNames.STATEMENT_INVALID) {
                    ErrorNames.STATEMENT_INVALID -> ({ SQLException(message) })
                    RESTART_TRANSACTION -> ({ RestartTransaction() })
                    else -> throw ReplayError(".error", "a StatementInvalid or a RestartTransaction", "\"$name\"")
                }
            val times = step["times"]
            val count =
                (times as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 }
                    ?: throw ReplayError(".times", "a positive integer", times?.toString() ?: MISSING)
            repeat(count) { queue.addLast(error) }
            return JsonNull
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

        /** `DoubleEntry.account(identifier, scope:)` for an account reference with a string scope. */
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
    }
}
