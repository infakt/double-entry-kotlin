package pl.infakt.doubleentry.parity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import pl.infakt.doubleentry.Money

/**
 * The account-balances area: `account_balance_record` and `scopes_with_minimum_balance` (oracle/README.md, "The
 * balance ops") through `DoubleEntry.accountBalances`. The scenarios define their accounts and transfer rules,
 * transfer, move the clock and read balances first, so every other op runs on a scenario of the balances area
 * ([BalancesExecutor]), on the same database and configuration; the effects are its tables.
 */
object AccountBalancesExecutor : AreaExecutor {
    override fun start(input: JsonObject): ScenarioRun = Run(BalancesExecutor.start(input))

    private class Run(
        private val balances: BalancesExecutor.Run,
    ) : ScenarioRun {
        private val doubleEntry = balances.doubleEntry

        override fun run(step: JsonObject): JsonElement =
            when (step.string("op")) {
                "account_balance_record" -> accountBalanceRecord(step)
                "scopes_with_minimum_balance" -> scopesWithMinimumBalance(step)
                else -> balances.run(step)
            }

        override fun tables(): Map<String, JsonElement> = balances.tables()

        /**
         * `AccountBalance.find_by_account(DoubleEntry.account(...))&.balance` for the account reference flat in the
         * step: the Money of the record, or `null` when the account has none.
         */
        private fun accountBalanceRecord(step: JsonObject): JsonElement {
            val record = doubleEntry.accountBalances.findByAccount(balances.account(step))
            return record?.let { json(it.balance) } ?: JsonNull
        }

        /**
         * `AccountBalance.scopes_with_minimum_balance_for_account(Money.new(amount), identifier)`, the amount in the
         * default currency. The gem gives the scopes in no order, so they are sorted as the recorder sorts them: a
         * `null` scope first, then by text (U-14).
         */
        private fun scopesWithMinimumBalance(step: JsonObject): JsonElement {
            val value = step["amount"]
            val amount =
                (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                    ?: throw ReplayError(".amount", "an integer, in cents", value?.toString() ?: MISSING)
            val minimum = Money(amount, doubleEntry.configuration.defaultCurrency)
            val scopes =
                doubleEntry.accountBalances.scopesWithMinimumBalanceForAccount(minimum, step.string("identifier"))
            return JsonArray(scopes.sortedWith(nullsFirst(naturalOrder())).map { JsonPrimitive(it) })
        }

        /** Money as the oracle writes it: `{"cents", "currency"}`. */
        private fun json(money: Money): JsonElement =
            JsonObject(
                mapOf(
                    "cents" to JsonPrimitive(money.minorUnits),
                    "currency" to JsonPrimitive(money.currency.currencyCode),
                ),
            )
    }
}
