package pl.infakt.doubleentry.parity

import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.sql.SQLException
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReplayTest {
    @TempDir
    lateinit var oracle: Path

    @Test
    fun `compare reports nothing when expected and actual are equal, whatever the key order`() {
        differences(
            expected = """{"steps": [1, {"a": "x"}], "lines": null}""",
            actual = """{"lines": null, "steps": [1, {"a": "x"}]}""",
        ) shouldBe emptyList()
    }

    @Test
    fun `compare reports a different value at its JSON path`() {
        differences(
            expected = """{"steps": [1, {"a": "x"}]}""",
            actual = """{"steps": [1, {"a": "y"}]}""",
        ) shouldBe listOf("$.steps[1].a: expected \"x\", got \"y\"")
    }

    @Test
    fun `compare reports a missing key, an extra key and a missing array element`() {
        differences(
            expected = """{"a": 1, "list": [1, 2]}""",
            actual = """{"b": 2, "list": [1]}""",
        ) shouldBe
            listOf(
                "$.a: expected 1, got <missing>",
                "$.b: expected <missing>, got 2",
                "$.list[1]: expected 2, got <missing>",
            )
    }

    @Test
    fun `compare reports a value of another kind whole`() {
        differences(
            expected = """{"a": [1]}""",
            actual = """{"a": {"x": 1}}""",
        ) shouldBe listOf("$.a: expected [1], got {\"x\":1}")
    }

    @Test
    fun `compare ignores the message of an expected error whose message is the engine marker`() {
        differences(
            expected = """{"error": {"class": "ActiveRecord::RecordNotUnique", "message": "<engine>"}}""",
            actual = """{"error": {"class": "ActiveRecord::RecordNotUnique", "message": "ERROR: duplicate key"}}""",
        ) shouldBe emptyList()
    }

    @Test
    fun `compare still compares the class of an expected error whose message is the engine marker`() {
        differences(
            expected = """{"error": {"class": "ActiveRecord::RecordNotUnique", "message": "<engine>"}}""",
            actual = """{"error": {"class": "ActiveRecord::NotNullViolation", "message": "ERROR: null value"}}""",
        ) shouldBe
            listOf("$.error.class: expected \"ActiveRecord::RecordNotUnique\", got \"ActiveRecord::NotNullViolation\"")
    }

    @Test
    fun `compare applies the engine marker to an exception inside an expected event`() {
        differences(
            expected = """{"events": [{"name": "rollback", "exception": {"class": "C", "message": "<engine>"}}]}""",
            actual = """{"events": [{"name": "rollback", "exception": {"class": "C", "message": "deadlock"}}]}""",
        ) shouldBe emptyList()
    }

    @Test
    fun `compare applies the engine marker to an event's exception class and message`() {
        differences(
            expected = """{"events": [{"exception_class": "C", "message": "<engine>", "name": "ignored"}]}""",
            actual = """{"events": [{"exception_class": "C", "message": "duplicate key", "name": "ignored"}]}""",
        ) shouldBe emptyList()
    }

    @Test
    fun `compare still compares an event's exception class whose message is the engine marker`() {
        differences(
            expected = """{"events": [{"exception_class": "C", "message": "<engine>", "name": "ignored"}]}""",
            actual = """{"events": [{"exception_class": "D", "message": "duplicate key", "name": "ignored"}]}""",
        ) shouldBe listOf("$.events[0].exception_class: expected \"C\", got \"D\"")
    }

    @Test
    fun `compare compares every other message exactly`() {
        differences(
            expected = """{"error": {"class": "DoubleEntry::UnknownAccount", "message": "account: savings"}}""",
            actual = """{"error": {"class": "DoubleEntry::UnknownAccount", "message": "account: checking"}}""",
        ) shouldBe listOf("$.error.message: expected \"account: savings\", got \"account: checking\"")
    }

    @Test
    fun `compare does not apply the engine marker to an object without a class`() {
        differences(
            expected = """{"message": "<engine>"}""",
            actual = """{"message": "deadlock"}""",
        ) shouldBe listOf("$.message: expected \"<engine>\", got \"deadlock\"")
    }

    @Test
    fun `replayScenario reports nothing when the steps, the effects and the outcome match`() {
        val scenario =
            scenarioOf(
                steps = """[{"op": "echo", "value": 1}, {"op": "echo", "value": "two"}]""",
                effects = """{"steps": [1]}""",
                outcome = """{"result": "two"}""",
            )

        replayScenario("s", scenario, Echo) shouldBe emptyList()
    }

    @Test
    fun `replayScenario reports each mismatch as scenario, path, expected and got`() {
        val scenario =
            scenarioOf(
                steps = """[{"op": "echo", "value": 1}, {"op": "echo", "value": 3}]""",
                effects = """{"steps": [9]}""",
                outcome = """{"result": 2}""",
            )

        replayScenario("s", scenario, Echo) shouldBe
            listOf(
                "s $.effects.steps[0]: expected 9, got 1",
                "s $.outcome.result: expected 2, got 3",
            )
    }

    @Test
    fun `replayScenario reports an unknown op as a mismatch at the step`() {
        val scenario =
            scenarioOf(
                steps = """[{"op": "echo", "value": 1}, {"op": "nope"}]""",
                effects = """{"steps": [1]}""",
                outcome = """{"result": null}""",
            )

        replayScenario("s", scenario, Echo) shouldBe
            listOf("s $.input.steps[1].op: expected a known op, got \"nope\"")
    }

    @Test
    fun `replayScenario matches a driver error against an expected engine error by class`() {
        val scenario =
            scenarioOf(
                steps = """[{"op": "raise", "message": "ERROR: duplicate key", "sqlState": "23505"}]""",
                effects = """{"steps": []}""",
                outcome = """{"error": {"class": "ActiveRecord::RecordNotUnique", "message": "<engine>"}}""",
            )

        replayScenario("s", scenario, Echo) shouldBe emptyList()
    }

    @Test
    fun `replayScenario stops at a step that throws before the last and reports the outcome missing`() {
        val scenario =
            scenarioOf(
                steps = """[{"op": "raise", "message": "boom", "sqlState": "42000"}, {"op": "echo", "value": 1}]""",
                effects = """{"steps": [{"error": {"class": "ActiveRecord::StatementInvalid", "message": "boom"}}]}""",
                outcome = """{"result": 1}""",
            )

        replayScenario("s", scenario, Echo) shouldBe listOf("s $.outcome: expected {\"result\":1}, got <missing>")
    }

    @Test
    fun `replayScenario accepts the known outcome of X-04 for its scenario only, and only that outcome`() {
        val recorded =
            """{"error": {"class": "NoMethodError",""" +
                """ "message": "undefined method 'metadata' for an instance of DoubleEntry::Line"}}"""
        val scenario =
            scenarioOf(
                steps = """[{"op": "echo", "value": null}]""",
                effects = """{"steps": []}""",
                outcome = recorded,
            )
        val other =
            scenarioOf(
                steps = """[{"op": "echo", "value": 1}]""",
                effects = """{"steps": []}""",
                outcome = recorded,
            )

        replayScenario("metadata-table-generator-schema", scenario, Echo) shouldBe emptyList()
        replayScenario("metadata-table-generator-schema", other, Echo) shouldBe
            listOf(
                "metadata-table-generator-schema $.outcome.error: expected ${json(recorded).jsonObject["error"]}, " +
                    "got <missing>",
                "metadata-table-generator-schema $.outcome.result: expected <missing>, got 1",
            )
        replayScenario("metadata-table-pairs", scenario, Echo).size shouldBe 2
    }

    @Test
    fun `replay passes and exits 0 when the scenarios of the areas on match, skipping the areas off`() {
        write(
            """{"areas": {"configuration": "on", "transfer-rules": "off"}}""",
            """
                {"id": "configuration-get", "area": "configuration",
                 "input": {"steps": [{"op": "get_config", "key": "code_max_length"}]},
                 "effects": {"steps": []}, "outcome": {"result": null}}
                """,
            """{"id": "transfer-rules-off", "area": "transfer-rules", "input": {"steps": [{"op": "nope"}]}}""",
        )

        val report = replay(oracle)

        report.mismatches shouldBe emptyList()
        report.counts() shouldBe "parity: 1 scenarios, 0 mismatches"
        report.exitCode() shouldBe 0
    }

    @Test
    fun `replay reports a mismatch of a scenario and exits 1`() {
        write(
            """{"areas": {"configuration": "on"}}""",
            """
                {"id": "configuration-json", "area": "configuration",
                 "input": {"steps": [{"op": "get_config", "key": "json_metadata"}]},
                 "effects": {"steps": []}, "outcome": {"result": true}}
                """,
        )

        val report = replay(oracle)

        report.mismatches shouldBe listOf("configuration-json $.outcome.result: expected true, got false")
        report.counts() shouldBe "parity: 1 scenarios, 1 mismatches"
        report.exitCode() shouldBe 1
    }

    @Test
    fun `replay exits 1 on an unknown op`() {
        write(
            """{"areas": {"configuration": "on"}}""",
            """
                {"id": "configuration-bogus", "area": "configuration", "input": {"steps": [{"op": "bogus"}]},
                 "effects": {"steps": []}, "outcome": {"result": null}}
                """,
        )

        val report = replay(oracle)

        report.mismatches shouldBe listOf("configuration-bogus $.input.steps[0].op: expected a known op, got \"bogus\"")
        report.exitCode() shouldBe 1
    }

    @Test
    fun `replay exits 1 on an area that is on with no executor`() {
        write("""{"areas": {"configuration": "on", "no-such-area": "on"}}""")

        val report = replay(oracle)

        report.mismatches shouldBe
            listOf("enabled.json $.areas.no-such-area: expected an area with an executor, got \"on\"")
        report.counts() shouldBe "parity: 0 scenarios, 1 mismatches"
        report.exitCode() shouldBe 1
    }

    private fun differences(
        expected: String,
        actual: String,
    ): List<String> {
        val found = mutableListOf<String>()
        compare("$", json(expected), json(actual)) { found += it }
        return found
    }

    private fun scenarioOf(
        steps: String,
        effects: String,
        outcome: String,
    ): JsonObject = json("""{"input": {"steps": $steps}, "effects": $effects, "outcome": $outcome}""").jsonObject

    private fun write(
        enabled: String,
        vararg scenarios: String,
    ) {
        oracle.resolve("enabled.json").writeText(enabled)
        val directory = oracle.resolve("scenarios").createDirectories()
        scenarios.forEachIndexed { index, text -> directory.resolve("scenario-$index.json").writeText(text) }
    }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    /** Echoes a step's `value` and throws an SQLException on `raise`; any other op is unknown. */
    private object Echo : AreaExecutor {
        override fun start(input: JsonObject): ScenarioRun =
            object : ScenarioRun {
                override fun run(step: JsonObject): JsonElement =
                    when (step.string("op")) {
                        "echo" -> step.getValue("value")
                        "raise" -> throw SQLException(step.string("message"), step.string("sqlState"))
                        else -> unknownOp(step)
                    }
            }
    }
}
