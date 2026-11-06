package pl.infakt.doubleentry.parity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class SchemaExecutorTest {
    @Test
    fun `the recorded schema scenario with json metadata replays without a mismatch`() {
        replayed("schema-json-metadata") shouldBe emptyList()
    }

    @Test
    fun `the recorded schema scenario with the line metadata table replays without a mismatch`() {
        replayed("schema-metadata-table") shouldBe emptyList()
    }

    @Test
    fun `type normalizes PostgreSQL's types of the port's columns as the oracle does`() {
        SchemaExecutor.type("character varying") shouldBe "string"
        SchemaExecutor.type("bigint") shouldBe "integer"
        SchemaExecutor.type("integer") shouldBe "integer"
        SchemaExecutor.type("boolean") shouldBe "boolean"
        SchemaExecutor.type("text") shouldBe "text"
        SchemaExecutor.type("json") shouldBe "json"
        SchemaExecutor.type("jsonb") shouldBe "json"
        SchemaExecutor.type("timestamp without time zone") shouldBe "datetime"
        SchemaExecutor.type("timestamp with time zone") shouldBe "datetime"
    }

    @Test
    fun `type shows a type the oracle has no name for as PostgreSQL's, so it reports as a mismatch`() {
        SchemaExecutor.type("smallint") shouldBe "smallint"
    }

    @Test
    fun `without input schema the scenario runs on the generator shape without json metadata`() {
        val run = SchemaExecutor.start(JsonObject(emptyMap()))

        run.run(json("""{"op": "describe_schema"}""")).jsonObject.keys shouldBe
            setOf(
                "double_entry_account_balances",
                "double_entry_line_checks",
                "double_entry_line_metadata",
                "double_entry_lines",
            )
    }

    @Test
    fun `the tables are the four tables, each without rows`() {
        val run = SchemaExecutor.start(JsonObject(emptyMap()))

        run.tables() shouldBe
            mapOf(
                "double_entry_account_balances" to JsonArray(emptyList()),
                "double_entry_line_checks" to JsonArray(emptyList()),
                "double_entry_line_metadata" to JsonArray(emptyList()),
                "double_entry_lines" to JsonArray(emptyList()),
            )
    }

    @Test
    fun `an op of another area is an unknown op`() {
        val run = SchemaExecutor.start(JsonObject(emptyMap()))

        val error = shouldThrow<ReplayError> { run.run(json("""{"op": "define_account", "identifier": "savings"}""")) }

        error.path shouldBe ".op"
    }

    @Test
    fun `the spec shape is not one the port creates`() {
        shouldThrow<IllegalArgumentException> { SchemaExecutor.start(json("""{"schema": {"shape": "spec"}}""")) }
    }

    private fun replayed(id: String): List<String> {
        val scenario = json(Path.of("oracle/scenarios/$id.json").readText())
        return replayScenario(id, scenario, SchemaExecutor)
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
