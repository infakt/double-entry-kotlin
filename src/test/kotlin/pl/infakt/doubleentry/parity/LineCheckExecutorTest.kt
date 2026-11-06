package pl.infakt.doubleentry.parity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class LineCheckExecutorTest {
    @Test
    fun `the recorded scenario of a balance record error replays without a mismatch`() {
        replayed("line-check-balance-error") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of consistent lines replays without a mismatch`() {
        replayed("line-check-consistent") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of the last line id checked replays without a mismatch`() {
        replayed("line-check-last-line-id") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a line error replays without a mismatch`() {
        replayed("line-check-line-error") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of no new lines replays without a mismatch`() {
        replayed("line-check-nothing-new") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of another currency replays without a mismatch`() {
        replayed("line-check-other-currency") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a line error in another currency replays without a mismatch`() {
        replayed("line-check-other-currency-line-error") shouldBe emptyList()
    }

    @Test
    fun `the two inspect lines of each error on a line are masked, the rest of the log is kept`() {
        val entry = "*********************************\nError on line #3: balance:1.01 != 0.00 + 1.00\n"
        val account = "*********************************\nError on account #{Account account: a}: 1 != 2\n"
        val log =
            entry + "*********************************\n  [Line(id=1)]\n  Line(id=3)\n\n" +
                account + "*********************************\n\n" +
                entry + "*********************************\n  []\n  Line(id=3)\n\n"

        LineCheckExecutor.masked(log) shouldBe
            entry + "*********************************\n  <masked>\n\n" +
            account + "*********************************\n\n" +
            entry + "*********************************\n  <masked>\n\n"
    }

    @Test
    fun `a line check with no new line is null`() {
        val run = LineCheckExecutor.start(JsonObject(emptyMap()))

        run.run(json("""{"op": "line_check", "fixer": false}""")) shouldBe JsonNull
    }

    @Test
    fun `a line check with a fixer is a replay error, the fixer is the account-fixer area's`() {
        val run = LineCheckExecutor.start(JsonObject(emptyMap()))

        val error = shouldThrow<ReplayError> { run.run(json("""{"op": "line_check", "fixer": true}""")) }

        error.path shouldBe ".fixer"
    }

    @Test
    fun `an sql update of another table is a replay error`() {
        val run = LineCheckExecutor.start(JsonObject(emptyMap()))

        val step = json("""{"op": "sql_update", "table": "double_entry_line_checks", "delta": 1}""")
        val error = shouldThrow<ReplayError> { run.run(step) }

        error.path shouldBe ".table"
    }

    private fun replayed(id: String): List<String> {
        val scenario = json(Path.of("oracle/scenarios/$id.json").readText())
        return replayScenario(id, scenario, LineCheckExecutor)
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
