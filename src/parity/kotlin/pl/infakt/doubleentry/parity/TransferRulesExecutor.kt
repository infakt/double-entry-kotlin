package pl.infakt.doubleentry.parity

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pl.infakt.doubleentry.DoubleEntryConfiguration
import pl.infakt.doubleentry.TransferRule

/**
 * The transfer-rules area: `define_transfer`, `find_transfer` and `find_transfer!` on the transfer rules of a fresh
 * [DoubleEntryConfiguration] per scenario, and the configuration area's ops on the same configuration.
 */
object TransferRulesExecutor : AreaExecutor {
    override fun start(input: JsonObject): ScenarioRun = Run(DoubleEntryConfiguration())

    private class Run(
        private val config: DoubleEntryConfiguration,
    ) : ScenarioRun {
        private val configuration = ConfigurationExecutor.start(config)

        override fun run(step: JsonObject): JsonElement {
            val rules = config.transfers
            return when (step.string("op")) {
                "define_transfer" -> json(rules.define(step.string("from"), step.string("to"), step.string("code")))
                "find_transfer" -> json(rules.find(step.string("from"), step.string("to"), code(step)))
                "find_transfer!" -> json(rules.findOrThrow(step.string("from"), step.string("to"), code(step)))
                else -> configuration.run(step)
            }
        }

        /** The step's `code`; a JSON null is the gem's `nil` code. */
        private fun code(step: JsonObject): String? = if (step["code"] is JsonNull) null else step.string("code")

        /** A rule as the oracle writes a `Transfer`: `{"code", "from", "to"}`, or `null` for none. */
        private fun json(rule: TransferRule?): JsonElement {
            if (rule == null) return JsonNull
            val fields = mapOf("code" to rule.code, "from" to rule.from, "to" to rule.to)
            return JsonObject(fields.mapValues { JsonPrimitive(it.value) })
        }
    }
}
