package pl.infakt.doubleentry.parity

import java.util.Currency
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import pl.infakt.doubleentry.Account
import pl.infakt.doubleentry.AccountInstance
import pl.infakt.doubleentry.DoubleEntry
import pl.infakt.doubleentry.DoubleEntryConfiguration

/**
 * The accounts area: `define_account`, `account`, `account_attr`, `account_to_s`, `account_currency`, `sort_accounts`,
 * `accounts_equal` and `hash_equal` on the accounts of a fresh [DoubleEntryConfiguration] per scenario, and the
 * configuration area's ops on the same configuration (oracle/README.md, "Ops"). Instances come from a [DoubleEntry]
 * facade over that configuration, as the recorder calls `DoubleEntry.account`.
 */
object AccountsExecutor : AreaExecutor {
    override fun start(input: JsonObject): ScenarioRun = Run(DoubleEntryConfiguration())

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

    private class Run(
        private val config: DoubleEntryConfiguration,
    ) : ScenarioRun {
        private val configuration = ConfigurationExecutor.start(config)

        private val doubleEntry = DoubleEntry(unconnectedDatabase, config)

        override fun run(step: JsonObject): JsonElement =
            when (step.string("op")) {
                "define_account" -> json(define(step))
                "account" -> json(account(step))
                "account_attr" -> attribute(account(step), step.string("attr"))
                "account_to_s" -> JsonPrimitive(account(step).toString())
                "account_currency" ->
                    config.accounts.currency(step.string("identifier"))?.let { JsonPrimitive(it.currencyCode) }
                        ?: JsonNull
                "sort_accounts" -> JsonArray(references(step).map { account(it) }.sorted().map { json(it) })
                "accounts_equal" -> JsonPrimitive(side(step, "left") == side(step, "right"))
                "hash_equal" -> JsonPrimitive(side(step, "left").hashCode() == side(step, "right").hashCode())
                else -> configuration.run(step)
            }

        /** The instance of the account reference nested in the step under [key], `left` or `right`. */
        private fun side(
            step: JsonObject,
            key: String,
        ): AccountInstance = account(reference(step, key))

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

        /** The account reference nested in the step under [key]. */
        private fun reference(
            step: JsonObject,
            key: String,
        ): JsonObject = step[key] as? JsonObject ?: throw ReplayError(".$key", "an account reference", show(step[key]))

        /** The account references of `sort_accounts`. */
        private fun references(step: JsonObject): List<JsonObject> {
            val accounts =
                step["accounts"] as? JsonArray ?: throw ReplayError(".accounts", "an array", show(step["accounts"]))
            return accounts.mapIndexed { index, each ->
                each as? JsonObject ?: throw ReplayError(".accounts[$index]", "an account reference", each.toString())
            }
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

        private fun attribute(
            instance: AccountInstance,
            attr: String,
        ): JsonElement =
            when (attr) {
                "identifier" -> JsonPrimitive(instance.identifier)
                "scope_identity" -> JsonPrimitive(instance.scopeIdentity)
                "currency" -> JsonPrimitive(instance.currency.currencyCode)
                "scoped?" -> JsonPrimitive(instance.isScoped)
                "positive_only" -> given(instance.positiveOnly)
                "negative_only" -> given(instance.negativeOnly)
                else -> throw ReplayError(".attr", "a known account attribute", "\"$attr\"")
            }

        /** An account as the oracle writes a definition: `{"currency", "identifier", "negative_only", …}`. */
        private fun json(account: Account): JsonElement =
            JsonObject(
                mapOf(
                    "currency" to JsonPrimitive(account.currency.currencyCode),
                    "identifier" to JsonPrimitive(account.identifier),
                    "negative_only" to given(account.negativeOnly),
                    "positive_only" to given(account.positiveOnly),
                    "scoped" to JsonPrimitive(account.isScoped),
                ),
            )

        /** An instance as the oracle writes an `Account::Instance`: `{"currency", "identifier", "scope_identity"}`. */
        private fun json(instance: AccountInstance): JsonElement =
            JsonObject(
                mapOf(
                    "currency" to JsonPrimitive(instance.currency.currencyCode),
                    "identifier" to JsonPrimitive(instance.identifier),
                    "scope_identity" to JsonPrimitive(instance.scopeIdentity),
                ),
            )

        /** A flag the oracle records as `null` when not given; the port's `false`, as no scenario gives `false`. */
        private fun given(flag: Boolean): JsonElement = if (flag) JsonPrimitive(true) else JsonNull

        private fun show(value: JsonElement?): String = value?.toString() ?: MISSING
    }
}
