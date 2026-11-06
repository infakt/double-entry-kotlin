package pl.infakt.doubleentry.parity

import kotlinx.serialization.json.JsonObject

/**
 * The account-fixer area: the ops of the line-check area ([LineCheckExecutor]), where `line_check` with `fixer: true`
 * runs `DoubleEntry.lineCheck.perform(DoubleEntry.accountFixer)`, the gem's `LineCheck.perform!(fixer:
 * AccountFixer.new)` (oracle/README.md, "Line checks"). The check, its log and the effects are as the line-check area
 * writes them; the balances after the check are the fixer's.
 */
object AccountFixerExecutor : AreaExecutor {
    override fun start(input: JsonObject): ScenarioRun = LineCheckExecutor.startWithFixer(input)
}
