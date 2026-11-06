package pl.infakt.doubleentry.parity

import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Replays the recorded scenarios of the areas on in `oracle/enabled.json` against the port, checks the inventory
 * ([InventoryCheck]) and prints `parity: N scenarios, M mismatches`, each mismatch and each `inventory` line, then the
 * counts again. The inventory's lines count as mismatches. Exits 1 on any mismatch.
 * Run it from the repository root (the Gradle task `parity` does).
 */
fun main() {
    val oracle = Path.of("oracle")
    val replayed = replay(oracle)
    val report = Report(replayed.scenarios, replayed.mismatches + InventoryCheck.check(oracle))
    println(report.counts())
    report.mismatches.forEach { println(it) }
    println(report.counts())
    exitProcess(report.exitCode())
}

/** What a replay found: how many scenarios it ran and every mismatch, as `<scenario> <path>: expected <x>, got <y>`. */
class Report(
    val scenarios: Int,
    val mismatches: List<String>,
) {
    fun counts(): String = "parity: $scenarios scenarios, ${mismatches.size} mismatches"

    /** The harness's exit status: 1 on any mismatch, 0 otherwise. */
    fun exitCode(): Int = if (mismatches.isEmpty()) 0 else 1
}

/** Replays the scenarios under [oracle] of every area that is on. */
fun replay(oracle: Path): Report {
    val mismatches = mutableListOf<String>()
    val areas = parse(oracle.resolve("enabled.json")).jsonObject.getValue("areas").jsonObject
    val on = areas.filterValues { it == JsonPrimitive("on") }.keys
    on.filterNot { it in Areas.executors }.sorted().forEach {
        mismatches += "enabled.json $.areas.$it: expected an area with an executor, got \"on\""
    }
    var scenarios = 0
    oracle
        .resolve("scenarios")
        .listDirectoryEntries()
        .filter { it.extension == "json" }
        .sorted()
        .forEach { file ->
            val scenario = parse(file).jsonObject
            val id = (scenario["id"] as? JsonPrimitive)?.content ?: file.nameWithoutExtension
            val area = (scenario["area"] as? JsonPrimitive)?.content
            when {
                area == null -> mismatches += "$id $.area: expected a recorded scenario, got $MISSING"

                area !in on -> Unit

                else -> {
                    scenarios++
                    Areas.executors[area]?.let { mismatches += replayScenario(id, scenario, it) }
                }
            }
        }
    return Report(scenarios, mismatches)
}

/**
 * Runs one scenario through [executor] and compares its steps' values, its outcome and its tables with the record. An
 * outcome [KnownDeviations] accepts is not compared.
 */
fun replayScenario(
    id: String,
    scenario: JsonObject,
    executor: AreaExecutor,
): List<String> {
    val input = scenario.getValue("input").jsonObject
    val steps = input.getValue("steps").jsonArray
    val run = executor.start(input)
    val values = mutableListOf<JsonElement>()
    var outcome: JsonElement? = null
    for ((index, step) in steps.withIndex()) {
        val last = index == steps.lastIndex
        val value =
            try {
                JsonObject(mapOf("result" to run.run(step.jsonObject)))
            } catch (error: ReplayError) {
                return listOf("$id $.input.steps[$index]${error.path}: expected ${error.expected}, got ${error.got}")
            } catch (error: Exception) {
                JsonObject(mapOf("error" to errorOf(error)))
            }
        if (last) {
            outcome = value
        } else {
            values += value["result"] ?: value
            if ("error" in value) break
        }
    }
    val effects = JsonObject(mapOf("steps" to JsonArray(values)) + run.tables())
    val mismatches = mutableListOf<String>()
    compare("$.effects", scenario["effects"], effects) { mismatches += "$id $it" }
    if (!KnownDeviations.accepts(id, scenario["outcome"], outcome)) {
        compare("$.outcome", scenario["outcome"], outcome) { mismatches += "$id $it" }
    }
    return mismatches
}

/**
 * The outcomes where the port differs from the gem on purpose (docs/deviations.md), each for one scenario: the outcome
 * the oracle recorded and the one the port gives instead. The replay accepts the port's outcome only for that
 * scenario, and only while the record and the port's outcome are exactly those; anything else is compared as usual.
 */
object KnownDeviations {
    private class Outcome(
        val recorded: JsonElement,
        val port: JsonElement,
    )

    /** The message of the gem's `NoMethodError` for `Line#metadata`, in the wording of the Ruby that recorded it. */
    private const val NO_METADATA_READER = "undefined method 'metadata' for an instance of DoubleEntry::Line"

    private val outcomes: Map<String, Outcome> =
        mapOf(
            // X-04: Line#metadata on the generator's tables without json_metadata; the port's line has a null one.
            "metadata-table-generator-schema" to
                Outcome(
                    recorded =
                        outcome(
                            "error",
                            JsonObject(
                                mapOf(
                                    "class" to JsonPrimitive("NoMethodError"),
                                    "message" to JsonPrimitive(NO_METADATA_READER),
                                ),
                            ),
                        ),
                    port = outcome("result", JsonNull),
                ),
        )

    /** Whether [actual] of the scenario [id] is the known deviation from its [recorded] outcome. */
    fun accepts(
        id: String,
        recorded: JsonElement?,
        actual: JsonElement?,
    ): Boolean = outcomes[id]?.let { it.recorded == recorded && it.port == actual } == true

    private fun outcome(
        key: String,
        value: JsonElement,
    ): JsonElement = JsonObject(mapOf(key to value))
}

/** An exception of the port as the oracle writes an error: the gem class name and the message. */
fun errorOf(error: Throwable): JsonObject {
    val name = ErrorNames.gemName(error)
    // An exception raised without a message has Ruby's default, the class name.
    return JsonObject(mapOf("class" to JsonPrimitive(name), "message" to JsonPrimitive(error.message ?: name)))
}

/** The message the oracle records for an error of the database engine or its driver; only its class is compared. */
const val ENGINE = "<engine>"

/**
 * Compares [actual] with [expected] in depth and reports each difference at its JSON path as
 * `<path>: expected <x>, got <y>`. An expected `{"class", "message": "<engine>"}`, or an event's
 * `{"exception_class", "message": "<engine>"}`, compares only the class.
 */
fun compare(
    path: String,
    expected: JsonElement?,
    actual: JsonElement?,
    report: (String) -> Unit,
) {
    when {
        expected is JsonObject && actual is JsonObject -> {
            val classed = "class" in expected || "exception_class" in expected
            val engine = classed && expected["message"] == JsonPrimitive(ENGINE)
            (expected.keys + actual.keys)
                .sorted()
                .filterNot { engine && it == "message" }
                .forEach { compare("$path.$it", expected[it], actual[it], report) }
        }

        expected is JsonArray && actual is JsonArray -> {
            for (index in 0 until maxOf(expected.size, actual.size)) {
                compare("$path[$index]", expected.getOrNull(index), actual.getOrNull(index), report)
            }
        }

        expected != actual -> report("$path: expected ${show(expected)}, got ${show(actual)}")
    }
}

private fun show(value: JsonElement?): String = value?.toString() ?: MISSING

private fun parse(file: Path): JsonElement = Json.parseToJsonElement(file.readText())
