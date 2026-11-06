package pl.infakt.doubleentry.parity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class NestedLockingExecutorTest {
    @Test
    fun `the recorded scenario of a transfer inside a lock replays without a mismatch`() {
        replayed("nested-locking-transfer-inside-lock") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a transfer inside a lock that does not hold it replays without a mismatch`() {
        replayed("nested-locking-transfer-lock-not-held") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a nested lock the outer lock covers replays without a mismatch`() {
        replayed("nested-locking-nested-covered") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a nested lock the outer lock does not cover replays without a mismatch`() {
        replayed("nested-locking-nested-not-covered") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a rollback replays without a mismatch`() {
        replayed("nested-locking-rollback") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a rollback on an exception replays without a mismatch`() {
        replayed("nested-locking-rollback-on-exception") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of the transactional fixtures flag replays without a mismatch`() {
        replayed("nested-locking-transactional-fixtures") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a lock wait timeout replays without a mismatch`() {
        replayed("nested-locking-lock-wait-timeout") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of another statement error replays without a mismatch`() {
        replayed("nested-locking-other-statement-error") shouldBe emptyList()
    }

    @Test
    fun `an injected error at create! belongs to another area and is a replay error`() {
        val run = NestedLockingExecutor.start(JsonObject(emptyMap()))

        val error =
            shouldThrow<ReplayError> {
                run.run(json("""{"op": "inject_error", "target": "create!", "message": "deadlock", "times": 1}"""))
            }

        error.path shouldBe ".target"
    }

    @Test
    fun `an injected RestartTransaction belongs to another area and is a replay error`() {
        val run = NestedLockingExecutor.start(JsonObject(emptyMap()))
        val step =
            """
            {"op": "inject_error", "target": "with_restart_on_deadlock", "message": "deadlock", "times": 1,
             "error": "ActiveRecord::RestartTransaction"}
            """.trimIndent()

        val error = shouldThrow<ReplayError> { run.run(json(step)) }

        error.path shouldBe ".error"
    }

    @Test
    fun `a rollback outside a lock is a replay error`() {
        val run = NestedLockingExecutor.start(JsonObject(emptyMap()))

        val error = shouldThrow<ReplayError> { run.run(json("""{"op": "rollback"}""")) }

        error.path shouldBe ".op"
    }

    @Test
    fun `an op of another area is an unknown op`() {
        val run = NestedLockingExecutor.start(JsonObject(emptyMap()))

        val error = shouldThrow<ReplayError> { run.run(json("""{"op": "describe_schema"}""")) }

        error.path shouldBe ".op"
    }

    private fun replayed(id: String): List<String> {
        val scenario = json(Path.of("oracle/scenarios/$id.json").readText())
        return replayScenario(id, scenario, NestedLockingExecutor)
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
