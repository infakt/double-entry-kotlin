package pl.infakt.doubleentry.parity

import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class AccountFixerExecutorTest {
    @Test
    fun `the recorded scenario of a balance record error replays without a mismatch`() {
        replayed("account-fixer-balance-error") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a line error replays without a mismatch`() {
        replayed("account-fixer-line-error") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a line error in another currency replays without a mismatch`() {
        replayed("account-fixer-other-currency-line-error") shouldBe emptyList()
    }

    @Test
    fun `a line check with a fixer and no new line is null`() {
        val run = AccountFixerExecutor.start(JsonObject(emptyMap()))

        run.run(json("""{"op": "line_check", "fixer": true}""")) shouldBe JsonNull
    }

    @Test
    fun `the area is the account-fixer area of the executors`() {
        Areas.executors["account-fixer"] shouldBe AccountFixerExecutor
    }

    private fun replayed(id: String): List<String> {
        val scenario = json(Path.of("oracle/scenarios/$id.json").readText())
        return replayScenario(id, scenario, AccountFixerExecutor)
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
