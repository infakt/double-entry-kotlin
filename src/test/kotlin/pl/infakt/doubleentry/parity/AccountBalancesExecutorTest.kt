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

class AccountBalancesExecutorTest {
    @Test
    fun `the recorded scenario of a transfer between two scopes replays without a mismatch`() {
        replayed("account-balances-between-scopes") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of the currency of a balance record replays without a mismatch`() {
        replayed("account-balances-currency") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a minimum balance above the balance replays without a mismatch`() {
        replayed("account-balances-min-balance-above") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a minimum balance below the balance replays without a mismatch`() {
        replayed("account-balances-min-balance-below") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a minimum balance equal to the balance replays without a mismatch`() {
        replayed("account-balances-min-balance-equal") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of balance records matching the balances replays without a mismatch`() {
        replayed("account-balances-records-match") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of a transfer to the same owner's other account replays without a mismatch`() {
        replayed("account-balances-same-owner-other-account") shouldBe emptyList()
    }

    @Test
    fun `the recorded scenario of separately scoped balances replays without a mismatch`() {
        replayed("account-balances-scoped-separately") shouldBe emptyList()
    }

    @Test
    fun `the balance record of an account without one is null`() {
        val run = AccountBalancesExecutor.start(JsonObject(emptyMap()))
        run.run(json("""{"op": "define_account", "identifier": "cash"}"""))

        run.run(json("""{"op": "account_balance_record", "identifier": "cash"}""")) shouldBe JsonNull
    }

    @Test
    fun `the scopes are sorted, a null scope first, then by text`() {
        val run = AccountBalancesExecutor.start(JsonObject(emptyMap()))
        run.run(json("""{"op": "define_account", "identifier": "bank"}"""))
        run.run(json("""{"op": "define_account", "identifier": "wallet", "scope": "identity"}"""))
        run.run(json("""{"op": "define_transfer", "from": "bank", "to": "wallet", "code": "pay"}"""))
        // The wallet of scope "b" gets its record first, so the port gives "b" before "a".
        listOf("b", "a").forEach { scope ->
            val from = """{"identifier": "bank"}"""
            val to = """{"identifier": "wallet", "scope": "$scope"}"""
            run.run(json("""{"op": "transfer", "amount": 100, "code": "pay", "from": $from, "to": $to}"""))
        }

        val wallets = run.run(json("""{"op": "scopes_with_minimum_balance", "amount": 0, "identifier": "wallet"}"""))
        val banks = run.run(json("""{"op": "scopes_with_minimum_balance", "amount": -200, "identifier": "bank"}"""))

        wallets shouldBe Json.parseToJsonElement("""["a", "b"]""")
        banks shouldBe Json.parseToJsonElement("""[null]""")
    }

    @Test
    fun `a minimum balance whose amount is not an integer is a replay error`() {
        val run = AccountBalancesExecutor.start(JsonObject(emptyMap()))

        val step = json("""{"op": "scopes_with_minimum_balance", "amount": "100", "identifier": "cash"}""")
        val error = shouldThrow<ReplayError> { run.run(step) }

        error.path shouldBe ".amount"
    }

    @Test
    fun `an op of another area is an unknown op`() {
        val run = AccountBalancesExecutor.start(JsonObject(emptyMap()))

        val error = shouldThrow<ReplayError> { run.run(json("""{"op": "describe_schema"}""")) }

        error.path shouldBe ".op"
    }

    private fun replayed(id: String): List<String> {
        val scenario = json(Path.of("oracle/scenarios/$id.json").readText())
        return replayScenario(id, scenario, AccountBalancesExecutor)
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
