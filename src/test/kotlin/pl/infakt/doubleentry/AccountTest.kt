package pl.infakt.doubleentry

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.util.Currency
import org.junit.jupiter.api.Test

class AccountTest {
    private val configuration = DoubleEntryConfiguration()

    private val identityScope: (Any) -> Any? = { it }

    private val usd = Currency.getInstance("USD")

    @Test
    fun `given an account_identifier_max_length of 31, an identifier 31 characters in length is accepted`() {
        configuration.accountIdentifierMaxLength = 31
        val identifier = "xxxxxxxx 31 characters xxxxxxxx"

        configuration.accounts.define(identifier).identifier shouldBe identifier
    }

    @Test
    fun `given an account_identifier_max_length of 31, an identifier of 32 characters raises the too-long error`() {
        configuration.accountIdentifierMaxLength = 31
        val identifier = "xxxxxxxx 32 characters xxxxxxxxx"

        val error = shouldThrow<AccountIdentifierTooLongException> { configuration.accounts.define(identifier) }

        error.message shouldContain "'$identifier'"
        error.message shouldBe "account identifier '$identifier' is too long. Please limit it to 31 characters."
    }

    @Test
    fun `the account identifier length is counted in characters, not in UTF-16 units`() {
        configuration.accountIdentifierMaxLength = 1

        configuration.accounts.define("𝄞").identifier shouldBe "𝄞"
    }

    @Test
    fun `an account instance is sortable`() {
        val account = Account("savings", identityScope, currency = usd)
        val a = AccountInstance(account, scope = "123")
        val b = AccountInstance(account, scope = "456")

        listOf(b, a).sorted() shouldBe listOf(a, b)
    }

    @Test
    fun `account instances sort by scope identity, then identifier, an unscoped one first`() {
        val savings = Account("savings", identityScope, currency = usd)
        val checking = Account("checking", identityScope, currency = usd)
        val cash = Account("cash", currency = usd)
        val sorted =
            listOf(
                AccountInstance(cash),
                AccountInstance(savings, scope = "123"),
                AccountInstance(checking, scope = "456"),
                AccountInstance(savings, scope = "456"),
            )

        sorted.reversed().sorted() shouldBe sorted
    }

    @Test
    fun `an account instance is hashable`() {
        val account = Account("savings", identityScope, currency = usd)
        val a1 = AccountInstance(account, scope = "123")
        val a2 = AccountInstance(account, scope = "123")
        val b = AccountInstance(account, scope = "456")

        a1.hashCode() shouldBe a2.hashCode()
        a1.hashCode() shouldNotBe b.hashCode()
        a1 shouldBe a2
        a1 shouldNotBe b
    }

    @Test
    fun `given a scope_identifier_max_length of 23, a scope identifier 23 characters in length is accepted`() {
        val account = Account("x", identityScope, currency = usd)
        val scope = "xxxx 23 characters xxxx"

        shouldNotThrowAny { AccountInstance(account, scope = scope, scopeIdentifierMaxLength = 23) }
    }

    @Test
    fun `given a scope_identifier_max_length of 23, a scope identifier of 24 characters raises the too-long error`() {
        val account = Account("x", identityScope, currency = usd)
        val scope = "xxxx 24 characters xxxxx"

        val error =
            shouldThrow<ScopeIdentifierTooLongException> {
                AccountInstance(account, scope = scope, scopeIdentifierMaxLength = 23)
            }

        error.message shouldContain "'$scope'"
        error.message shouldBe "scope identifier '$scope' is too long. Please limit it to 23 characters."
    }

    @Test
    fun `the scope identifier max length is read from the configuration when an instance is built`() {
        configuration.scopeIdentifierMaxLength = 23
        configuration.accounts { define("x", identityScope) }

        shouldThrow<ScopeIdentifierTooLongException> {
            configuration.accounts.account("x", scope = "xxxx 24 characters xxxxx")
        }
    }

    @Test
    fun `the currency defaults to USD`() {
        val account = configuration.accounts.define("savings", identityScope)

        AccountInstance(account).currency shouldBe Currency.getInstance("USD")
    }

    @Test
    fun `the currency can be set`() {
        val account = configuration.accounts.define("savings", identityScope, currency = Currency.getInstance("AUD"))

        AccountInstance(account).currency shouldBe Currency.getInstance("AUD")
    }

    @Test
    fun `the default currency is read when an account is defined`() {
        configuration.defaultCurrency = Currency.getInstance("EUR")
        val account = configuration.accounts.define("savings")
        configuration.defaultCurrency = Currency.getInstance("USD")

        account.currency shouldBe Currency.getInstance("EUR")
    }

    @Test
    fun `currency gives the currency of the account with an identifier, scoped or not, or null for an unknown one`() {
        val bhd = Currency.getInstance("BHD")
        configuration.accounts {
            define("cash")
            define("bhd_savings", identityScope, currency = bhd)
        }

        configuration.accounts.currency("cash") shouldBe usd
        configuration.accounts.currency("bhd_savings") shouldBe bhd
        configuration.accounts.currency("invalid") shouldBe null
    }

    @Test
    fun `Set find finds unscoped accounts`() {
        val set = definedSet()

        set.find("savings", false).identifier shouldBe "savings"
        shouldThrow<UnknownAccountException> { set.find("savings", true) }
    }

    @Test
    fun `Set find finds scoped accounts`() {
        val set = definedSet()

        set.find("checking", true).identifier shouldBe "checking"
        shouldThrow<UnknownAccountException> { set.find("checking", false) }
    }

    @Test
    fun `UnknownAccountException names the identifier and the scoped flag`() {
        val error = shouldThrow<UnknownAccountException> { definedSet().find("invalid", true) }

        error.message shouldBe "account: invalid scoped?: true"
    }

    @Test
    fun `defining the same identifier twice raises DuplicateAccountException`() {
        shouldThrow<DuplicateAccountException> {
            configuration.accounts {
                define("gah!")
                define("gah!")
            }
        }
    }

    @Test
    fun `findWithoutScope and all give the defined accounts in definition order`() {
        val set = definedSet()

        set.findWithoutScope("checking")?.identifier shouldBe "checking"
        set.findWithoutScope("invalid") shouldBe null
        set.all().map { it.identifier } shouldBe listOf("savings", "checking")
    }

    @Test
    fun `account finds an unscoped account without a scope, and a scoped one with a scope or a scope identity`() {
        val set = definedSet()

        set.account("savings").scopeIdentity shouldBe null
        set.account("checking", scope = 1).scopeIdentity shouldBe "1"
        set.account("checking", scopeIdentity = "7").scopeIdentity shouldBe "7"
        shouldThrow<UnknownAccountException> { set.account("savings", scope = 1) }
        shouldThrow<UnknownAccountException> { set.account("checking") }
    }

    @Test
    fun `a blank scope or scope identity counts as none, and a given blank scope identity is kept`() {
        val set = definedSet()

        set.account("savings", scope = "").scopeIdentity shouldBe null
        set.account("savings", scopeIdentity = " ").scopeIdentity shouldBe " "
        val error = shouldThrow<UnknownAccountException> { set.account("checking", scope = "") }

        error.message shouldBe "account: checking scoped?: false"
    }

    @Test
    fun `a scope identifier that returns null gives an empty scope identity`() {
        val account = Account("savings", { null }, currency = usd)

        AccountInstance(account, scope = "123").scopeIdentity shouldBe ""
    }

    @Test
    fun `a scope identifier that throws makes account throw the same exception`() {
        val error = IllegalStateException("not a User")
        configuration.accounts { define("savings", { throw error }) }

        val thrown = shouldThrow<IllegalStateException> { configuration.accounts.account("savings", scope = "7") }

        thrown shouldBeSameInstanceAs error
    }

    @Test
    fun `toString writes the identifier, the scope as given and the currency`() {
        configuration.accounts {
            define("cash")
            define("savings", identityScope, currency = Currency.getInstance("AUD"))
        }

        configuration.accounts.account("cash").toString() shouldBe "#{Account account: cash scope:  currency: USD}"
        configuration.accounts.account("savings", scope = "123").toString() shouldBe
            "#{Account account: savings scope: 123 currency: AUD}"
    }

    @Test
    fun `an instance gives the flags and currency of its account`() {
        val account = Account("savings", positiveOnly = true, currency = usd)
        val instance = AccountInstance(account)

        instance.positiveOnly shouldBe true
        instance.negativeOnly shouldBe false
        instance.isScoped shouldBe false
        instance.currency shouldBe usd
    }

    private fun definedSet(): Accounts =
        Accounts().apply {
            define("savings")
            define("checking", scopeIdentifier = { it })
        }
}
