package pl.infakt.doubleentry.parity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.TransferCodeTooLongException
import pl.infakt.doubleentry.TransferNotAllowedException

class TransferRulesExecutorTest {
    private val run = TransferRulesExecutor.start(JsonObject(emptyMap()))

    @Test
    fun `define_transfer returns the rule as the oracle writes a Transfer`() {
        step("""{"op": "define_transfer", "from": "savings", "to": "cash", "code": "xfer"}""") shouldBe
            json("""{"code": "xfer", "from": "savings", "to": "cash"}""")
    }

    @Test
    fun `find_transfer returns a defined rule, and null for one that is not`() {
        step("""{"op": "define_transfer", "from": "savings", "to": "cash", "code": "xfer"}""")

        step("""{"op": "find_transfer", "from": "savings", "to": "cash", "code": "xfer"}""") shouldBe
            json("""{"code": "xfer", "from": "savings", "to": "cash"}""")
        step("""{"op": "find_transfer", "from": "cash", "to": "savings", "code": "xfer"}""") shouldBe JsonNull
        step("""{"op": "find_transfer", "from": "savings", "to": "cash", "code": null}""") shouldBe JsonNull
    }

    @Test
    fun `find_transfer! throws TransferNotAllowedException for a rule that is not defined`() {
        val error =
            shouldThrow<TransferNotAllowedException> {
                step("""{"op": "find_transfer!", "from": "cash", "to": "savings", "code": "xfer"}""")
            }

        error.message shouldBe "[:cash, :savings, :xfer]"
    }

    @Test
    fun `set_config runs on the configuration whose transfer rules the scenario defines`() {
        step("""{"op": "set_config", "key": "code_max_length", "value": 3}""") shouldBe JsonNull

        shouldThrow<TransferCodeTooLongException> {
            step("""{"op": "define_transfer", "from": "savings", "to": "cash", "code": "four"}""")
        }
    }

    @Test
    fun `each scenario starts with no rules`() {
        step("""{"op": "define_transfer", "from": "savings", "to": "cash", "code": "xfer"}""")

        val next = TransferRulesExecutor.start(JsonObject(emptyMap()))

        next.run(json("""{"op": "find_transfer", "from": "savings", "to": "cash", "code": "xfer"}""")) shouldBe JsonNull
    }

    @Test
    fun `an unknown op is a replay error at op`() {
        val error = shouldThrow<ReplayError> { step("""{"op": "nope"}""") }

        error.path shouldBe ".op"
        error.got shouldBe "\"nope\""
    }

    private fun step(text: String): JsonElement = run.run(json(text))

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
