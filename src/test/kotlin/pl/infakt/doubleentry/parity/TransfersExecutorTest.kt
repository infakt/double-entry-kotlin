package pl.infakt.doubleentry.parity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class TransfersExecutorTest {
    @Test
    fun `the recorded scenario of an allowed transfer replays without a mismatch`() {
        replayed("transfers-allowed") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a transfer against its rule's direction replays without a mismatch`() {
        replayed("transfers-wrong-direction") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a transfer with a wrong code replays without a mismatch`() {
        replayed("transfers-wrong-code") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a transfer without a code replays without a mismatch`() {
        replayed("transfers-without-code") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a transfer between currencies replays without a mismatch`() {
        replayed("transfers-mismatched-currencies") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a negative amount replays without a mismatch`() {
        replayed("transfers-negative-amount") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a zero amount replays without a mismatch`() {
        replayed("transfers-zero-amount") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a transfer to the same account replays without a mismatch`() {
        replayed("transfers-identical-account") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a transfer between two scopes of an account replays without a mismatch`() {
        replayed("transfers-same-identifier-other-scope") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario returning the credit and the debit replays without a mismatch`() {
        replayed("transfers-returns-credit-and-debit") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a transfer with a detail replays without a mismatch`() {
        replayed("transfers-with-detail") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a transfer inside a transaction replays without a mismatch`() {
        replayed("transfers-inside-transaction") shouldBe emptyList()
    }

    @Test
    fun `a detail other than an invoice is a replay error`() {
        val run = TransfersExecutor.start(JsonObject(emptyMap()))
        run.run(json("""{"op": "define_account", "identifier": "savings"}"""))
        run.run(json("""{"op": "define_account", "identifier": "cash"}"""))
        run.run(json("""{"op": "define_transfer", "from": "savings", "to": "cash", "code": "xfer"}"""))

        val accounts = """"from": {"identifier": "savings"}, "to": {"identifier": "cash"}"""
        val step = json("""{"op": "transfer", "amount": 100, $accounts, "code": "xfer", "detail": {"order": 1}}""")

        val error = shouldThrow<ReplayError> { run.run(step) }

        error.path shouldBe ".detail"
    }

    @Test
    fun `an op of another area is an unknown op`() {
        val run = TransfersExecutor.start(JsonObject(emptyMap()))

        val error = shouldThrow<ReplayError> { run.run(json("""{"op": "describe_schema"}""")) }

        error.path shouldBe ".op"
    }

    private fun replayed(id: String): List<String> {
        val scenario = json(Path.of("oracle/scenarios/$id.json").readText())
        return replayScenario(id, scenario, TransfersExecutor)
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
