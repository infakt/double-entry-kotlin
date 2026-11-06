package pl.infakt.doubleentry.parity

import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class MetadataJsonExecutorTest {
    @Test
    fun `the recorded scenario of a transfer without metadata replays without a mismatch`() {
        replayed("metadata-json-none") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of two key and value pairs replays without a mismatch`() {
        replayed("metadata-json-pairs") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of an array of values for one key replays without a mismatch`() {
        replayed("metadata-json-array-values") shouldBe emptyList()
    }

    @Test
    fun `the area is replayed by its executor`() {
        Areas.executors["metadata-json"] shouldBe MetadataJsonExecutor
    }

    @Test
    fun `with json_metadata set, a transfer's metadata is both lines' column and no line_metadata row`() {
        val run = MetadataJsonExecutor.start(json("""{"schema": {"shape": "spec"}}"""))
        run.run(json("""{"op": "set_config", "key": "json_metadata", "value": true}"""))
        run.run(json("""{"op": "define_account", "identifier": "a"}"""))
        run.run(json("""{"op": "define_account", "identifier": "b"}"""))
        run.run(json("""{"op": "define_transfer", "from": "a", "to": "b", "code": "xfer"}"""))
        run.run(
            json(
                """{"op": "transfer", "amount": 100, "from": {"identifier": "a"}, "to": {"identifier": "b"},""" +
                    """ "code": "xfer", "metadata": {"tax": ["GST", "VAT"]}}""",
            ),
        )

        run.run(json("""{"op": "line_metadata_count"}""")) shouldBe JsonPrimitive(0L)
        run.run(json("""{"op": "line_metadata_of", "line": 1}""")) shouldBe json("""{"tax": ["GST", "VAT"]}""")
        run.run(json("""{"op": "line_metadata_of", "line": 2}""")) shouldBe json("""{"tax": ["GST", "VAT"]}""")
    }

    private fun replayed(id: String): List<String> {
        val scenario = json(Path.of("oracle/scenarios/$id.json").readText())
        return replayScenario(id, scenario, MetadataJsonExecutor)
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
