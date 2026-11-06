package pl.infakt.doubleentry.parity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class BalancesExecutorTest {
    @Test
    fun `the recorded scenario of the balances after a transfer replays without a mismatch`() {
        replayed("balances-after-transfer") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a balance at a time replays without a mismatch`() {
        replayed("balances-at") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a balance at a time beside a range replays without a mismatch`() {
        replayed("balances-at-ignores-range") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a balance between two times replays without a mismatch`() {
        replayed("balances-between") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a balance between now and the future replays without a mismatch`() {
        replayed("balances-between-future") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a balance by identifier and scope replays without a mismatch`() {
        replayed("balances-by-identifier-and-scope") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a balance by code replays without a mismatch`() {
        replayed("balances-code") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a balance by codes replays without a mismatch`() {
        replayed("balances-codes") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of the initial balances replays without a mismatch`() {
        replayed("balances-initial") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of the running balance of each line replays without a mismatch`() {
        replayed("balances-running-per-line") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of the balance of a scoped account without a scope replays without a mismatch`() {
        replayed("balances-scoped-without-scope") shouldBe emptyList()
    }

    @Test
    fun `a balance with both an account and an identifier is a replay error`() {
        val run = BalancesExecutor.start(JsonObject(emptyMap()))
        run.run(json("""{"op": "define_account", "identifier": "cash"}"""))

        val step = json("""{"op": "balance", "account": {"identifier": "cash"}, "identifier": "cash"}""")
        val error = shouldThrow<ReplayError> { run.run(step) }

        error.path shouldBe ".account"
    }

    @Test
    fun `a balance at a time that is not ISO 8601 is a replay error`() {
        val run = BalancesExecutor.start(JsonObject(emptyMap()))
        run.run(json("""{"op": "define_account", "identifier": "cash"}"""))

        val step = json("""{"op": "balance", "account": {"identifier": "cash"}, "at": "1.week.ago"}""")
        val error = shouldThrow<ReplayError> { run.run(step) }

        error.path shouldBe ".at"
    }

    @Test
    fun `an op of another area is an unknown op`() {
        val run = BalancesExecutor.start(JsonObject(emptyMap()))

        val error = shouldThrow<ReplayError> { run.run(json("""{"op": "describe_schema"}""")) }

        error.path shouldBe ".op"
    }

    private fun replayed(id: String): List<String> {
        val scenario = json(Path.of("oracle/scenarios/$id.json").readText())
        return replayScenario(id, scenario, BalancesExecutor)
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
