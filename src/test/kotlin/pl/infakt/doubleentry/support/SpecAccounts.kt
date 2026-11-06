package pl.infakt.doubleentry.support

import java.util.Currency
import org.jetbrains.exposed.v1.jdbc.Database
import pl.infakt.doubleentry.DoubleEntry
import pl.infakt.doubleentry.DoubleEntryConfiguration

/** The spec suite's user (spec/support/factories.rb): a scope whose scope identity is its [id]. Made-up ids only. */
data class TestUser(
    val id: Long,
)

/**
 * The accounts and transfer rules the gem's specs share: spec/support/accounts.rb, with BHD and `bhd_` names in place
 * of the BTC accounts and their transfer code (docs/deviations.md, X-02). Every account is scoped by a [TestUser].
 */
object SpecAccounts {
    private val bhd: Currency = Currency.getInstance("BHD")

    /** `lambda { |user| user.id }` (accounts.rb:6). */
    private val userScope: (Any) -> Any? = { user -> (user as TestUser).id }

    /** Defines the accounts (accounts.rb:5-14) and the transfer rules (accounts.rb:17-24) on [configuration]. */
    fun define(configuration: DoubleEntryConfiguration) {
        configuration.accounts {
            define("savings", scopeIdentifier = userScope, positiveOnly = true)
            define("checking", scopeIdentifier = userScope, positiveOnly = true)
            define("test", scopeIdentifier = userScope)
            define("bhd_test", scopeIdentifier = userScope, currency = bhd)
            define("bhd_savings", scopeIdentifier = userScope, currency = bhd)
            define("deposit_fees", scopeIdentifier = userScope, positiveOnly = true)
            define("account_fees", scopeIdentifier = userScope, positiveOnly = true)
        }
        configuration.transfers {
            define("test", "savings", "bonus")
            define("test", "checking", "pay")
            define("savings", "test", "test_withdrawal")
            define("bhd_test", "bhd_savings", "bhd_test_transfer")
            define("savings", "deposit_fees", "fee")
            define("savings", "account_fees", "fee")
        }
    }

    /** A facade over [database] with the spec accounts, then whatever [configure] sets up. */
    fun doubleEntry(
        database: Database,
        configure: DoubleEntryConfiguration.() -> Unit = {},
    ): DoubleEntry =
        DoubleEntry(database) {
            define(this)
            configure()
        }
}
