package pl.infakt.doubleentry.parity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.AccountWouldBeSentNegativeException

class LinesExecutorTest {
    @Test
    fun `the recorded scenario of a line's account instances replays without a mismatch`() {
        replayed("lines-account-instances") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a line's amount and code replays without a mismatch`() {
        replayed("lines-amount-and-code") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of the credits and debits scopes replays without a mismatch`() {
        replayed("lines-credits-debits") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a line's currency replays without a mismatch`() {
        replayed("lines-currency") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of increase and decrease replays without a mismatch`() {
        replayed("lines-increase-decrease") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a negative-only account sent positive replays without a mismatch`() {
        replayed("lines-negative-only-violation") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a line without a code replays without a mismatch`() {
        replayed("lines-null-code-rejected") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a line's partner and pair replays without a mismatch`() {
        replayed("lines-partner-and-pair") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a positive-only account sent negative replays without a mismatch`() {
        replayed("lines-positive-only-violation") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a line's scope columns replays without a mismatch`() {
        replayed("lines-scope-columns") shouldBe emptyList()
    }

    @Test
    fun `a line without a code is the gem's NotNullViolation, its message not compared`() {
        val run = LinesExecutor.start(JsonObject(emptyMap()))
        run.run(json("""{"op": "define_account", "identifier": "test", "scope": "user_id"}"""))
        val accounts =
            """"account": {"identifier": "test", "scope_identity": "17"},""" +
                """ "partner_account": {"identifier": "test", "scope_identity": "72"}"""
        val step = json("""{"op": "seed_line", $accounts, "amount": 1000, "balance": 0, "code": null}""")

        val error = shouldThrow<Exception> { run.run(step) }

        error.shouldBeInstanceOf<ExposedSQLException>().sqlState shouldBe "23502"
        errorOf(error)["class"] shouldBe JsonPrimitive("ActiveRecord::NotNullViolation")
    }

    @Test
    fun `line_save runs the balance check before it needs the partner account`() {
        val run = LinesExecutor.start(JsonObject(emptyMap()))
        run.run(json("""{"op": "define_account", "identifier": "positive", "positive_only": true}"""))

        shouldThrow<AccountWouldBeSentNegativeException> {
            run.run(json("""{"op": "line_save", "account": {"identifier": "positive"}, "balance": -1}"""))
        }
    }

    @Test
    fun `line_save of a line that passes the check without a partner account is a replay error`() {
        val run = LinesExecutor.start(JsonObject(emptyMap()))
        run.run(json("""{"op": "define_account", "identifier": "positive", "positive_only": true}"""))

        val error =
            shouldThrow<ReplayError> {
                run.run(json("""{"op": "line_save", "account": {"identifier": "positive"}, "balance": 1}"""))
            }

        error.path shouldBe ".partner_account"
    }

    @Test
    fun `line_attr with both an attr and a column is a replay error`() {
        val run = transferred()

        val step = json("""{"op": "line_attr", "line": 1, "attr": "code", "column": "code"}""")

        val error = shouldThrow<ReplayError> { run.run(step) }

        error.path shouldBe ".attr"
    }

    @Test
    fun `line_attr of a line that is not stored is a replay error`() {
        val run = transferred()

        val error = shouldThrow<ReplayError> { run.run(json("""{"op": "line_attr", "line": 9, "attr": "code"}""")) }

        error.path shouldBe ".line"
    }

    @Test
    fun `lines_scope other than credits or debits is a replay error`() {
        val run = transferred()

        val error = shouldThrow<ReplayError> { run.run(json("""{"op": "lines_scope", "scope": "all"}""")) }

        error.path shouldBe ".scope"
    }

    @Test
    fun `an op of another area is an unknown op`() {
        val run = LinesExecutor.start(JsonObject(emptyMap()))

        val error = shouldThrow<ReplayError> { run.run(json("""{"op": "describe_schema"}""")) }

        error.path shouldBe ".op"
    }

    /** A run with one transfer from `a` to `b`, its lines 1 and 2. */
    private fun transferred(): ScenarioRun {
        val run = LinesExecutor.start(JsonObject(emptyMap()))
        run.run(json("""{"op": "define_account", "identifier": "a"}"""))
        run.run(json("""{"op": "define_account", "identifier": "b"}"""))
        run.run(json("""{"op": "define_transfer", "from": "a", "to": "b", "code": "xfer"}"""))
        val accounts = """"from": {"identifier": "a"}, "to": {"identifier": "b"}"""
        run.run(json("""{"op": "transfer", "amount": 100, $accounts, "code": "xfer"}"""))
        return run
    }

    private fun replayed(id: String): List<String> {
        val scenario = json(Path.of("oracle/scenarios/$id.json").readText())
        return replayScenario(id, scenario, LinesExecutor)
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
