package pl.infakt.doubleentry.parity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class LockingExtensionsExecutorTest {
    @Test
    fun `the recorded scenario of a restartable transaction replays without a mismatch`() {
        replayed("locking-extensions-restartable-transaction") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenarios of a deadlock restarting the lock replay without a mismatch`() {
        adapters.flatMap { replayed("locking-extensions-deadlock-restart-$it") } shouldBe emptyList()
    }

    @Test
    fun `the recorded scenarios of a deadlock retrying the create replay without a mismatch`() {
        adapters.flatMap { replayed("locking-extensions-deadlock-retry-$it") } shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of an ignored duplicate replays without a mismatch`() {
        replayed("locking-extensions-ignore-duplicate") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of another error re-raised replays without a mismatch`() {
        replayed("locking-extensions-other-error-reraised") shouldBe emptyList()
    }

    @Test
    fun `an injected error at an unknown target is a replay error`() {
        val run = LockingExtensionsExecutor.start(JsonObject(emptyMap()))

        val error =
            shouldThrow<ReplayError> {
                run.run(json("""{"op": "inject_error", "target": "save!", "message": "deadlock", "times": 1}"""))
            }

        error.path shouldBe ".target"
    }

    @Test
    fun `an injected error of an unknown class is a replay error`() {
        val run = LockingExtensionsExecutor.start(JsonObject(emptyMap()))
        val step =
            """
            {"op": "inject_error", "target": "create!", "message": "deadlock", "times": 1,
             "error": "ActiveRecord::Rollback"}
            """.trimIndent()

        val error = shouldThrow<ReplayError> { run.run(json(step)) }

        error.path shouldBe ".error"
    }

    @Test
    fun `an injected error without a positive count is a replay error`() {
        val run = LockingExtensionsExecutor.start(JsonObject(emptyMap()))

        val error =
            shouldThrow<ReplayError> {
                run.run(json("""{"op": "inject_error", "target": "create!", "message": "deadlock", "times": 0}"""))
            }

        error.path shouldBe ".times"
    }

    @Test
    fun `an op of another area is an unknown op`() {
        val run = LockingExtensionsExecutor.start(JsonObject(emptyMap()))

        val error = shouldThrow<ReplayError> { run.run(json("""{"op": "describe_schema"}""")) }

        error.path shouldBe ".op"
    }

    private fun replayed(id: String): List<String> {
        val scenario = json(Path.of("oracle/scenarios/$id.json").readText())
        return replayScenario(id, scenario, LockingExtensionsExecutor)
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private companion object {
        val adapters = listOf("mysql", "postgres", "sqlite")
    }
}
