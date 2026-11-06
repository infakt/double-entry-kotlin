package pl.infakt.doubleentry

import java.util.Currency
import kotlin.time.Clock

/**
 * The library's settings, in one place: the gem's `DoubleEntry::Configuration` (configuration.rb), the max lengths it
 * delegates to `Account` and `Transfer`, `DoubleEntry::Locking::Configuration` (locking.rb:21-29), the default
 * currency the gem takes from `Money.default_currency` (account.rb:148) and the clock it reads through `Time.now`.
 */
class DoubleEntryConfiguration {
    /** The longest transfer code allowed, or `null` for no limit (transfer.rb:7, 72-75). */
    var codeMaxLength: Int? = null

    /** The longest scope identity allowed, or `null` for no limit (account.rb:7, 134-137). */
    var scopeIdentifierMaxLength: Int? = null

    /** The longest account identifier allowed, or `null` for no limit (account.rb:7, 149-152). */
    var accountIdentifierMaxLength: Int? = null

    /** Whether lines keep their metadata in a JSON column rather than the metadata table (configuration.rb:6-10). */
    var jsonMetadata: Boolean = false

    /**
     * Set this in tests that run inside a transaction, so locking accounts does not complain about a containing
     * transaction (locking.rb:22-28).
     */
    var runningInsideTransactionalFixtures: Boolean = false

    /** The currency of an account defined without one (account.rb:148). */
    var defaultCurrency: Currency = Currency.getInstance("USD")

    /** The source of the current time. */
    var clock: Clock = Clock.System

    /**
     * Told of each deadlock and duplicate that locking accounts handled: the gem's notifications of
     * locking_extensions.rb, by default logged as its `LogSubscriber` does ([DoubleEntryEvents.Logging]).
     */
    var events: DoubleEntryEvents = DoubleEntryEvents.Logging

    /** The accounts: the gem's `accounts`, an `Account::Set` (configuration.rb:12-20). */
    val accounts: Accounts =
        Accounts({ accountIdentifierMaxLength }, { scopeIdentifierMaxLength }, { defaultCurrency })

    /** Defines accounts in [block], which receives [accounts]: `define_accounts` (configuration.rb:30-32). */
    fun accounts(block: Accounts.() -> Unit) {
        accounts.block()
    }

    /** The transfer rules: the gem's `transfers`, a `Transfer::Set` (configuration.rb:22-28). */
    val transfers: TransferRules = TransferRules { codeMaxLength }

    /** Defines transfer rules in [block], which receives [transfers]: `define_transfers` (configuration.rb:34-36). */
    fun transfers(block: TransferRules.() -> Unit) {
        transfers.block()
    }
}
