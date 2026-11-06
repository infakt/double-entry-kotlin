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

class LockingExecutorTest {
    @Test
    fun `the recorded scenario creating a missing balance replays without a mismatch`() {
        replayed("locking-creates-missing-balance") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario taking the balance from the lines replays without a mismatch`() {
        replayed("locking-balance-from-lines") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario locking inside a transaction replays without a mismatch`() {
        replayed("locking-inside-transaction") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario locking a scoped and an unscoped account replays without a mismatch`() {
        replayed("locking-scoped-and-unscoped") shouldBe emptyList()
    }

    @Test
    fun `without a step the tables are the four tables, each without rows`() {
        val run = LockingExecutor.start(JsonObject(emptyMap()))

        run.tables() shouldBe
            mapOf(
                "double_entry_account_balances" to JsonArray(emptyList()),
                "double_entry_line_checks" to JsonArray(emptyList()),
                "double_entry_line_metadata" to JsonArray(emptyList()),
                "double_entry_lines" to JsonArray(emptyList()),
            )
    }

    @Test
    fun `a scope the locking area does not use is a replay error`() {
        val run = LockingExecutor.start(JsonObject(emptyMap()))

        val error =
            shouldThrow<ReplayError> {
                run.run(json("""{"op": "define_account", "identifier": "savings", "scope": "user_id"}"""))
            }

        error.path shouldBe ".scope"
    }

    @Test
    fun `an op of another area is an unknown op`() {
        val run = LockingExecutor.start(JsonObject(emptyMap()))

        val error = shouldThrow<ReplayError> { run.run(json("""{"op": "describe_schema"}""")) }

        error.path shouldBe ".op"
    }

    private fun replayed(id: String): List<String> {
        val scenario = json(Path.of("oracle/scenarios/$id.json").readText())
        return replayScenario(id, scenario, LockingExecutor)
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
