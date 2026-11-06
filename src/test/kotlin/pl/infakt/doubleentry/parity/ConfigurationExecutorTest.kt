package pl.infakt.doubleentry.parity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class ConfigurationExecutorTest {
    private val run = ConfigurationExecutor.start(JsonObject(emptyMap()))

    @Test
    fun `get_config reads each key of a fresh configuration`() {
        step("""{"op": "get_config", "key": "code_max_length"}""") shouldBe JsonNull
        step("""{"op": "get_config", "key": "scope_identifier_max_length"}""") shouldBe JsonNull
        step("""{"op": "get_config", "key": "account_identifier_max_length"}""") shouldBe JsonNull
        step("""{"op": "get_config", "key": "json_metadata"}""") shouldBe JsonPrimitive(false)
        step("""{"op": "get_config", "key": "running_inside_transactional_fixtures"}""") shouldBe JsonPrimitive(false)
    }

    @Test
    fun `set_config returns null and get_config reads the value back`() {
        step("""{"op": "set_config", "key": "account_identifier_max_length", "value": 9}""") shouldBe JsonNull
        step("""{"op": "set_config", "key": "json_metadata", "value": true}""") shouldBe JsonNull

        step("""{"op": "get_config", "key": "account_identifier_max_length"}""") shouldBe JsonPrimitive(9)
        step("""{"op": "get_config", "key": "json_metadata"}""") shouldBe JsonPrimitive(true)
    }

    @Test
    fun `each scenario starts from a fresh configuration`() {
        step("""{"op": "set_config", "key": "code_max_length", "value": 10}""")

        val next = ConfigurationExecutor.start(JsonObject(emptyMap()))

        next.run(json("""{"op": "get_config", "key": "code_max_length"}""")) shouldBe JsonNull
    }

    @Test
    fun `an unknown op is a replay error at op`() {
        val error = shouldThrow<ReplayError> { step("""{"op": "nope"}""") }

        error.path shouldBe ".op"
        error.got shouldBe "\"nope\""
    }

    @Test
    fun `an unknown key is a replay error at key`() {
        val error = shouldThrow<ReplayError> { step("""{"op": "get_config", "key": "nope"}""") }

        error.path shouldBe ".key"
        error.got shouldBe "\"nope\""
    }

    @Test
    fun `a max length that is not an integer is a replay error at value`() {
        val error =
            shouldThrow<ReplayError> { step("""{"op": "set_config", "key": "code_max_length", "value": "10"}""") }

        error.path shouldBe ".value"
        error.got shouldBe "\"10\""
    }

    private fun step(text: String): JsonElement = run.run(json(text))

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
