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
import pl.infakt.doubleentry.AccountIdentifierTooLongException
import pl.infakt.doubleentry.UnknownAccountException

class AccountsExecutorTest {
    private val run = AccountsExecutor.start(JsonObject(emptyMap()))

    @Test
    fun `define_account returns the account as the oracle writes a definition`() {
        step("""{"op": "define_account", "identifier": "savings", "scope": "identity", "currency": "AUD"}""") shouldBe
            json(
                """{"currency": "AUD", "identifier": "savings", "negative_only": null, "positive_only": null,
                   "scoped": true}""",
            )
        step("""{"op": "define_account", "identifier": "cash", "positive_only": true}""") shouldBe
            json(
                """{"currency": "USD", "identifier": "cash", "negative_only": null, "positive_only": true,
                   "scoped": false}""",
            )
    }

    @Test
    fun `account returns the instance, with a user scope or a scope identity`() {
        step("""{"op": "define_account", "identifier": "scoped", "scope": "user_id"}""")

        step("""{"op": "account", "identifier": "scoped", "scope": {"user": 1}}""") shouldBe
            json("""{"currency": "USD", "identifier": "scoped", "scope_identity": "1"}""")
        step("""{"op": "account", "identifier": "scoped", "scope_identity": "7"}""") shouldBe
            json("""{"currency": "USD", "identifier": "scoped", "scope_identity": "7"}""")
    }

    @Test
    fun `account of an unscoped account has a null scope identity`() {
        step("""{"op": "define_account", "identifier": "cash"}""")

        step("""{"op": "account", "identifier": "cash"}""") shouldBe
            json("""{"currency": "USD", "identifier": "cash", "scope_identity": null}""")
    }

    @Test
    fun `account_attr reads an attribute of the instance`() {
        step("""{"op": "define_account", "identifier": "savings", "scope": "identity"}""")

        step("""{"op": "account_attr", "identifier": "savings", "scope": "123", "attr": "scope_identity"}""") shouldBe
            JsonPrimitive("123")
        step("""{"op": "account_attr", "identifier": "savings", "scope": "123", "attr": "scoped?"}""") shouldBe
            JsonPrimitive(true)
        step("""{"op": "account_attr", "identifier": "savings", "scope": "123", "attr": "currency"}""") shouldBe
            JsonPrimitive("USD")
        step("""{"op": "account_attr", "identifier": "savings", "scope": "123", "attr": "positive_only"}""") shouldBe
            JsonNull
    }

    @Test
    fun `account_to_s writes the instance's text`() {
        step("""{"op": "define_account", "identifier": "cash"}""")

        step("""{"op": "account_to_s", "identifier": "cash"}""") shouldBe
            JsonPrimitive("#{Account account: cash scope:  currency: USD}")
    }

    @Test
    fun `account_currency gives the currency code of a definition, scoped or not, or null`() {
        step("""{"op": "define_account", "identifier": "cash"}""")
        step("""{"op": "define_account", "identifier": "bhd_savings", "scope": "identity", "currency": "BHD"}""")

        step("""{"op": "account_currency", "identifier": "cash"}""") shouldBe JsonPrimitive("USD")
        step("""{"op": "account_currency", "identifier": "bhd_savings"}""") shouldBe JsonPrimitive("BHD")
        step("""{"op": "account_currency", "identifier": "invalid"}""") shouldBe JsonNull
    }

    @Test
    fun `sort_accounts sorts by scope identity, then identifier`() {
        step("""{"op": "define_account", "identifier": "savings", "scope": "identity"}""")
        step("""{"op": "define_account", "identifier": "cash"}""")

        val sorted =
            step(
                """{"op": "sort_accounts", "accounts": [{"identifier": "savings", "scope": "456"},
                   {"identifier": "savings", "scope": "123"}, {"identifier": "cash"}]}""",
            )

        sorted shouldBe
            Json.parseToJsonElement(
                """[{"currency": "USD", "identifier": "cash", "scope_identity": null},
                    {"currency": "USD", "identifier": "savings", "scope_identity": "123"},
                    {"currency": "USD", "identifier": "savings", "scope_identity": "456"}]""",
            )
    }

    @Test
    fun `accounts_equal and hash_equal compare two references`() {
        step("""{"op": "define_account", "identifier": "savings", "scope": "identity"}""")
        val left = """"left": {"identifier": "savings", "scope": "123"}"""
        val same = """$left, "right": {"identifier": "savings", "scope": "123"}"""
        val other = """$left, "right": {"identifier": "savings", "scope": "456"}"""

        step("""{"op": "accounts_equal", $same}""") shouldBe JsonPrimitive(true)
        step("""{"op": "accounts_equal", $other}""") shouldBe JsonPrimitive(false)
        step("""{"op": "hash_equal", $same}""") shouldBe JsonPrimitive(true)
        step("""{"op": "hash_equal", $other}""") shouldBe JsonPrimitive(false)
    }

    @Test
    fun `the user_only scope identifier raises Ruby's RuntimeError for a scope that is not a user`() {
        step("""{"op": "define_account", "identifier": "savings", "scope": "user_only"}""")

        val user = """{"op": "account_attr", "identifier": "savings", "scope": {"user": 7}, "attr": "scope_identity"}"""
        step(user) shouldBe JsonPrimitive("7")
        val error =
            shouldThrow<RuntimeException> { step("""{"op": "account", "identifier": "savings", "scope": "7"}""") }

        errorOf(error) shouldBe json("""{"class": "RuntimeError", "message": "not a User"}""")
    }

    @Test
    fun `an account that is not defined throws UnknownAccountException`() {
        val error = shouldThrow<UnknownAccountException> { step("""{"op": "account", "identifier": "invalid"}""") }

        error.message shouldBe "account: invalid scoped?: false"
    }

    @Test
    fun `set_config runs on the configuration whose accounts the scenario defines`() {
        step("""{"op": "set_config", "key": "account_identifier_max_length", "value": 3}""") shouldBe JsonNull

        shouldThrow<AccountIdentifierTooLongException> {
            step("""{"op": "define_account", "identifier": "four"}""")
        }
    }

    @Test
    fun `each scenario starts with no accounts`() {
        step("""{"op": "define_account", "identifier": "cash"}""")

        val next = AccountsExecutor.start(JsonObject(emptyMap()))

        shouldThrow<UnknownAccountException> { next.run(json("""{"op": "account", "identifier": "cash"}""")) }
    }

    @Test
    fun `an unknown scope is a replay error at scope`() {
        val error =
            shouldThrow<ReplayError> { step("""{"op": "define_account", "identifier": "cash", "scope": "nope"}""") }

        error.path shouldBe ".scope"
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
