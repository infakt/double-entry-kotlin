package pl.infakt.doubleentry.parity

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import pl.infakt.doubleentry.DoubleEntryConfiguration

/** The configuration area: `set_config` and `get_config` on a fresh [DoubleEntryConfiguration] per scenario. */
object ConfigurationExecutor : AreaExecutor {
    override fun start(input: JsonObject): ScenarioRun = start(DoubleEntryConfiguration())

    /** Runs `set_config` and `get_config` on [config], for an area that shares its configuration with them. */
    fun start(config: DoubleEntryConfiguration): ScenarioRun = Run(config)

    private class Run(
        private val config: DoubleEntryConfiguration,
    ) : ScenarioRun {
        override fun run(step: JsonObject): JsonElement =
            when (step.string("op")) {
                "set_config" -> set(step.string("key"), step)
                "get_config" -> get(step.string("key"))
                else -> unknownOp(step)
            }

        private fun get(key: String): JsonElement =
            when (key) {
                "code_max_length" -> JsonPrimitive(config.codeMaxLength)
                "scope_identifier_max_length" -> JsonPrimitive(config.scopeIdentifierMaxLength)
                "account_identifier_max_length" -> JsonPrimitive(config.accountIdentifierMaxLength)
                "json_metadata" -> JsonPrimitive(config.jsonMetadata)
                "running_inside_transactional_fixtures" -> JsonPrimitive(config.runningInsideTransactionalFixtures)
                else -> unknownKey(key)
            }

        /** Sets [key] to the step's `value`; the op's value is `null`. */
        private fun set(
            key: String,
            step: JsonObject,
        ): JsonElement {
            when (key) {
                "code_max_length" -> config.codeMaxLength = maxLength(step)
                "scope_identifier_max_length" -> config.scopeIdentifierMaxLength = maxLength(step)
                "account_identifier_max_length" -> config.accountIdentifierMaxLength = maxLength(step)
                "json_metadata" -> config.jsonMetadata = flag(step)
                "running_inside_transactional_fixtures" -> config.runningInsideTransactionalFixtures = flag(step)
                else -> unknownKey(key)
            }
            return JsonNull
        }

        /** An integer, or `null` to lift the limit, as the gem's specs reset it (configuration_spec.rb:28-32). */
        private fun maxLength(step: JsonObject): Int? {
            val value = step["value"] ?: throw ReplayError(".value", "an integer or null", MISSING)
            if (value is JsonNull) return null
            return (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                ?: throw ReplayError(".value", "an integer or null", value.toString())
        }

        private fun flag(step: JsonObject): Boolean {
            val value = step["value"] ?: throw ReplayError(".value", "a boolean", MISSING)
            return (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: throw ReplayError(".value", "a boolean", value.toString())
        }

        private fun unknownKey(key: String): Nothing = throw ReplayError(".key", "a known config key", "\"$key\"")
    }
}
