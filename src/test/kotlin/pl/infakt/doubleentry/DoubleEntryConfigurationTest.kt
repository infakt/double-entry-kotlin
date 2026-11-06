package pl.infakt.doubleentry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.util.Currency
import kotlin.time.Clock
import org.junit.jupiter.api.Test

class DoubleEntryConfigurationTest {
    private val configuration = DoubleEntryConfiguration()

    @Test
    fun `given a max length has not been set, code_max_length should be nil`() {
        configuration.codeMaxLength shouldBe null
    }

    @Test
    fun `given a max length has not been set, scope_identifier_max_length should be nil`() {
        configuration.scopeIdentifierMaxLength shouldBe null
    }

    @Test
    fun `given a max length has not been set, account_identifier_max_length should be nil`() {
        configuration.accountIdentifierMaxLength shouldBe null
    }

    @Test
    fun `given a code max length of 10 has been set, code_max_length should be 10`() {
        configuration.codeMaxLength = 10

        configuration.codeMaxLength shouldBe 10
    }

    @Test
    fun `given a scope identifier max length of 11 has been set, scope_identifier_max_length should be 11`() {
        configuration.scopeIdentifierMaxLength = 11

        configuration.scopeIdentifierMaxLength shouldBe 11
    }

    @Test
    fun `given an account identifier max length of 9 has been set, account_identifier_max_length should be 9`() {
        configuration.accountIdentifierMaxLength = 9

        configuration.accountIdentifierMaxLength shouldBe 9
    }

    @Test
    fun `json_metadata is false by default`() {
        configuration.jsonMetadata shouldBe false
    }

    @Test
    fun `running_inside_transactional_fixtures is false by default`() {
        configuration.runningInsideTransactionalFixtures shouldBe false
    }

    @Test
    fun `the default currency is USD`() {
        configuration.defaultCurrency shouldBe Currency.getInstance("USD")
    }

    @Test
    fun `the clock is the system clock by default`() {
        configuration.clock shouldBe Clock.System
    }

    @Test
    fun `the events are logged by default`() {
        configuration.events shouldBe DoubleEntryEvents.Logging
    }

    @Test
    fun `the accounts are an accounts set, empty at first`() {
        configuration.accounts.shouldBeInstanceOf<Accounts>()
        configuration.accounts.all() shouldBe emptyList()
    }

    @Test
    fun `the accounts block receives the accounts set`() {
        var received: Accounts? = null

        configuration.accounts { received = this }

        received shouldBeSameInstanceAs configuration.accounts
    }

    @Test
    fun `the accounts set reads the account identifier max length when an account is defined`() {
        configuration.accountIdentifierMaxLength = 3

        shouldThrow<AccountIdentifierTooLongException> { configuration.accounts { define("four") } }
    }

    @Test
    fun `the transfers are a transfer rule set`() {
        configuration.transfers.shouldBeInstanceOf<TransferRules>()
    }

    @Test
    fun `the transfers block receives the transfers set`() {
        var received: TransferRules? = null

        configuration.transfers { received = this }

        received shouldBeSameInstanceAs configuration.transfers
    }

    @Test
    fun `the transfers set reads the code max length when a rule is defined`() {
        configuration.codeMaxLength = 3

        shouldThrow<TransferCodeTooLongException> { configuration.transfers { define("from", "to", "four") } }
    }
}
