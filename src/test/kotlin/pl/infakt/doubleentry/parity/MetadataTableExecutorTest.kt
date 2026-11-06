package pl.infakt.doubleentry.parity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class MetadataTableExecutorTest {
    @Test
    fun `the recorded scenario of a transfer without metadata replays without a mismatch`() {
        replayed("metadata-table-none") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of two key and value pairs replays without a mismatch`() {
        replayed("metadata-table-pairs") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of an array of values for one key replays without a mismatch`() {
        replayed("metadata-table-array-values") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of the generator's tables replays with only its known deviation (X-04)`() {
        replayed("metadata-table-generator-schema") shouldBe emptyList()
    }

    @Test
    fun `on the generator's tables the port reads a null metadata where the gem raises NoMethodError (X-04)`() {
        val run = transferred("""{"schema": {"shape": "generator", "json_metadata": false}}""")

        run.run(json("""{"op": "line_metadata_count"}""")) shouldBe JsonPrimitive(2L)
        run.run(json("""{"op": "line_metadata_of", "line": 1}""")) shouldBe JsonNull
    }

    @Test
    fun `with json_metadata set on the spec shape, line_metadata_of reads the empty column as null`() {
        val run = transferred("""{"schema": {"shape": "spec"}}""")
        run.run(json("""{"op": "set_config", "key": "json_metadata", "value": true}"""))

        run.run(json("""{"op": "line_metadata_of", "line": 2}""")) shouldBe JsonNull
    }

    @Test
    fun `a metadata value that is neither a string nor an array of strings is a replay error`() {
        val run = defined("""{"schema": {"shape": "spec"}}""")

        val error = shouldThrow<ReplayError> { run.run(transfer(""""metadata": {"tax": 1}""")) }

        error.path shouldBe ".metadata.tax"
    }

    @Test
    fun `line_metadata_of a line that is not stored is a replay error`() {
        val run = transferred("""{"schema": {"shape": "spec"}}""")

        val error = shouldThrow<ReplayError> { run.run(json("""{"op": "line_metadata_of", "line": 9}""")) }

        error.path shouldBe ".line"
    }

    @Test
    fun `a shape other than the generator's and the spec's is rejected`() {
        shouldThrow<IllegalArgumentException> {
            MetadataTableExecutor.start(json("""{"schema": {"shape": "other"}}"""))
        }
    }

    @Test
    fun `an op of another area is an unknown op`() {
        val run = MetadataTableExecutor.start(JsonObject(emptyMap()))

        val error = shouldThrow<ReplayError> { run.run(json("""{"op": "describe_schema"}""")) }

        error.path shouldBe ".op"
    }

    /** A run on [input] with the accounts `a` and `b` and the rule from `a` to `b` under `xfer`. */
    private fun defined(input: String): ScenarioRun {
        val run = MetadataTableExecutor.start(json(input))
        run.run(json("""{"op": "define_account", "identifier": "a"}"""))
        run.run(json("""{"op": "define_account", "identifier": "b"}"""))
        run.run(json("""{"op": "define_transfer", "from": "a", "to": "b", "code": "xfer"}"""))
        return run
    }

    /** [defined], then one transfer with one metadata value: lines 1 and 2, a metadata row each. */
    private fun transferred(input: String): ScenarioRun {
        val run = defined(input)
        run.run(transfer(""""metadata": {"tax": "GST"}"""))
        return run
    }

    /** A transfer of 100 from `a` to `b` under `xfer`, with [extra] fields. */
    private fun transfer(extra: String): JsonObject =
        json(
            """{"op": "transfer", "amount": 100, "from": {"identifier": "a"}, "to": {"identifier": "b"},""" +
                """ "code": "xfer", $extra}""",
        )

    private fun replayed(id: String): List<String> {
        val scenario = json(Path.of("oracle/scenarios/$id.json").readText())
        return replayScenario(id, scenario, MetadataTableExecutor)
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
