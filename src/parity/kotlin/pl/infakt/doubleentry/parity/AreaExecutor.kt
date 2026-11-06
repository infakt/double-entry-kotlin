package pl.infakt.doubleentry.parity

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Drives the port through the ops of one area of the oracle (oracle/README.md, "Ops"). */
interface AreaExecutor {
    /**
     * Starts one scenario from the state the recorder resets the gem to before each scenario (oracle/README.md,
     * "State before each scenario"). [input] is the scenario's `input`.
     */
    fun start(input: JsonObject): ScenarioRun
}

/** One scenario in progress: its steps run in order against the same state. */
interface ScenarioRun {
    /**
     * Runs one step of `input.steps` and returns its value as the oracle writes it (oracle/README.md, "Values").
     * An exception the port throws propagates; a step the harness cannot run throws [ReplayError].
     */
    fun run(step: JsonObject): JsonElement

    /** The `effects.<table>` entries after the last step: every table the area records, by name. */
    fun tables(): Map<String, JsonElement> = emptyMap()
}

/**
 * A step the harness cannot run: an unknown op or an argument it cannot read. [path] is relative to the step
 * (`.op`, `.key`, …); the replay reports it as a mismatch.
 */
class ReplayError(
    val path: String,
    val expected: String,
    val got: String,
) : RuntimeException("$path: expected $expected, got $got")

/** A string argument of a step, such as its `op`. */
fun JsonObject.string(key: String): String {
    val value = this[key] ?: throw ReplayError(".$key", "a string", MISSING)
    if (value !is JsonPrimitive || !value.isString) throw ReplayError(".$key", "a string", value.toString())
    return value.content
}

/** Throws the [ReplayError] for an op the executor does not know. */
fun unknownOp(step: JsonObject): Nothing = throw ReplayError(".op", "a known op", step["op"]?.toString() ?: MISSING)

/** How a report shows a value that is not there. */
const val MISSING = "<missing>"
