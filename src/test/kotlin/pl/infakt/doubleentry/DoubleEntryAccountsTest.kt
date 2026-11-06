package pl.infakt.doubleentry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.parity.unconnectedDatabase

/** double_entry_spec.rb:17-89: the facade's configuration and its accounts. */
class DoubleEntryAccountsTest {
    /** The spec's `double('a scope', id: 1)`. */
    private data class User(
        val id: Int,
    )

    private val scope = User(1)

    private val doubleEntry =
        DoubleEntry(unconnectedDatabase) {
            accounts {
                define("unscoped")
                define("scoped", scopeIdentifier = { (it as User).id })
            }
        }

    @Test
    fun `configuration checks for duplicates of accounts`() {
        shouldThrow<DuplicateAccountException> {
            DoubleEntry(unconnectedDatabase) {
                accounts {
                    define("gah!")
                    define("gah!")
                }
            }
        }
    }

    @Test
    fun `configuration checks for duplicates of transfers`() {
        shouldThrow<DuplicateTransferException> {
            DoubleEntry(unconnectedDatabase) {
                transfers {
                    define(from = "savings", to = "cash", code = "xfer")
                    define(from = "savings", to = "cash", code = "xfer")
                }
            }
        }
    }

    @Test
    fun `accounts fetching can find an unscoped account by identifier`() {
        doubleEntry.account("unscoped").shouldNotBeNull()
    }

    @Test
    fun `accounts fetching can find a scoped account by identifier`() {
        doubleEntry.account("scoped", scope = scope).shouldNotBeNull()
    }

    @Test
    fun `accounts fetching raises an exception when it cannot find an account`() {
        shouldThrow<UnknownAccountException> { doubleEntry.account("invalid") }
    }

    @Test
    fun `accounts fetching raises exception when you ask for an unscoped account with a scope`() {
        shouldThrow<UnknownAccountException> { doubleEntry.account("unscoped", scope = scope) }
    }

    @Test
    fun `accounts fetching raises exception when you ask for a scoped account without a scope`() {
        shouldThrow<UnknownAccountException> { doubleEntry.account("scoped") }
    }

    @Test
    fun `an unscoped account has an identifier`() {
        doubleEntry.account("unscoped").identifier shouldBe "unscoped"
    }

    @Test
    fun `a scoped account has an identifier`() {
        doubleEntry.account("scoped", scope = scope).identifier shouldBe "scoped"
    }

    @Test
    fun `the facade keeps the database and the configuration it is given`() {
        val configuration = DoubleEntryConfiguration()

        val facade = DoubleEntry(unconnectedDatabase, configuration)

        facade.database shouldBeSameInstanceAs unconnectedDatabase
        facade.configuration shouldBeSameInstanceAs configuration
    }

    @Test
    fun `the builder sets up a new configuration of its own`() {
        val other = DoubleEntry(unconnectedDatabase) { codeMaxLength = 5 }

        other.configuration.codeMaxLength shouldBe 5
        doubleEntry.configuration.codeMaxLength shouldBe null
        shouldThrow<UnknownAccountException> { other.account("unscoped") }
    }

    @Test
    fun `account reads a scope identity given as is`() {
        doubleEntry.account("scoped", scopeIdentity = "7").scopeIdentity shouldBe "7"
    }
}
