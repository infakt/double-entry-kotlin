package pl.infakt.doubleentry

import kotlin.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database
import pl.infakt.doubleentry.validation.AccountFixer
import pl.infakt.doubleentry.validation.LineCheck

/**
 * The library's entry point: the gem's `DoubleEntry` module (double_entry.rb) over the caller's [database], with its
 * settings, accounts and transfer rules in [configuration]. The gem keeps one global configuration; the port keeps one
 * per instance. The facade never connects: the caller connects [database].
 */
class DoubleEntry(
    val database: Database,
    val configuration: DoubleEntryConfiguration,
) {
    /**
     * A facade over [database] with a new configuration that [configure] sets up: the gem's `DoubleEntry.configure`
     * (configurable.rb:46-48), which yields the configuration.
     *
     * @throws DuplicateAccountException when [configure] defines an account twice.
     * @throws DuplicateTransferException when [configure] defines a transfer rule twice.
     */
    constructor(
        database: Database,
        configure: DoubleEntryConfiguration.() -> Unit,
    ) : this(database, DoubleEntryConfiguration().apply(configure))

    /** The facade's locks; internal for the seam the tests and the parity harness use ([Locking.beforeGrabLocks]). */
    internal val locking = Locking(database, configuration)

    private val transfers = Transfers(database, configuration, locking)

    private val calculator = BalanceCalculator(database, configuration)

    private val lineMetadataRows = LineMetadataRows(database, configuration)

    /**
     * The lines of [database]: the gem's `DoubleEntry::Line` (line.rb), to write a line, read a line's partner and
     * pair, and list the lines by the gem's scopes.
     */
    val lines: Lines = Lines(database, configuration)

    /**
     * The account balance records of [database]: the gem's `DoubleEntry::AccountBalance` (account_balance.rb), to read
     * the record of an account and find the scopes of an account holding at least a minimum balance. Transfers and
     * locks write the records.
     */
    val accountBalances: AccountBalances = AccountBalances(database, configuration)

    /**
     * The line check of [database]: the gem's `DoubleEntry::Validation::LineCheck` (validation/line_check.rb), which
     * checks the running balances of the lines written since the last check and the balance records of their accounts,
     * and writes a row of `double_entry_line_checks` with its log.
     */
    val lineCheck: LineCheck = LineCheck(database, configuration, lines, locking)

    /**
     * The fixer of the line check: the gem's `DoubleEntry::Validation::AccountFixer` (validation/account_fixer.rb),
     * which, inside a lock of an account, rewrites the wrong running balances of its lines and sets its balance record
     * to its recalculated balance. `lineCheck.perform(accountFixer)` is the gem's `LineCheck.perform!(fixer:
     * AccountFixer.new)`.
     */
    val accountFixer: AccountFixer = AccountFixer(lines, locking, accountBalances)

    /**
     * The instance of the account [identifier] for [scope], or for [scopeIdentity] given as is: the gem's
     * `DoubleEntry.account` (double_entry.rb:51-53), as [Accounts.account] of [configuration]. The instance gives its
     * [AccountInstance.balance] from [database].
     *
     * @throws UnknownAccountException when no account [identifier] is defined, or it is scoped and no scope is given,
     *   or it is unscoped and one is.
     * @throws ScopeIdentifierTooLongException when the scope identity has more characters than allowed.
     */
    fun account(
        identifier: String,
        scope: Any? = null,
        scopeIdentity: String? = null,
    ): AccountInstance = configuration.accounts.instance(identifier, scope, scopeIdentity, calculator)

    /**
     * The current or historic balance of [account], in its currency: the gem's `DoubleEntry.balance`
     * (double_entry.rb:92-145, balance_calculator.rb:16-33). The lines are those of [account]'s identifier and, when it
     * has a scope identity, of that scope:
     * - with [at], the balance is the running balance of the last line created at or before it, [from] and [to]
     *   ignored;
     * - with [from] and [to] and no [at], it is the sum of the amounts of the lines created between them, both
     *   included;
     * - with [code] or [codes], it is the sum of the amounts of the lines of any of them, [code] being one more of
     *   [codes];
     * - otherwise it is the running balance of the last line by id.
     *
     * A balance without a line is zero.
     */
    fun balance(
        account: AccountInstance,
        from: Instant? = null,
        to: Instant? = null,
        at: Instant? = null,
        code: String? = null,
        codes: List<String> = emptyList(),
    ): Money = calculator.calculate(account, from, to, at, code, codes)

    /**
     * The balance, as [balance] of an instance gives it, of the account [identifier] for [scope], or for
     * [scopeIdentity] given as is: the gem's `DoubleEntry.balance` of a symbol (double_entry.rb:142-145), which finds
     * the account as [account] does.
     *
     * @throws UnknownAccountException when no account [identifier] is defined, or it is scoped and no scope is given,
     *   or it is unscoped and one is.
     * @throws ScopeIdentifierTooLongException when the scope identity has more characters than allowed.
     */
    fun balance(
        identifier: String,
        scope: Any? = null,
        scopeIdentity: String? = null,
        from: Instant? = null,
        to: Instant? = null,
        at: Instant? = null,
        code: String? = null,
        codes: List<String> = emptyList(),
    ): Money = calculator.calculate(account(identifier, scope, scopeIdentity), from, to, at, code, codes)

    /**
     * Runs [block] in a transaction of [database] with [accounts] locked for update, and returns its value: the gem's
     * `DoubleEntry.lock_accounts` (double_entry.rb:147-165, locking.rb:35-51). The accounts are locked in their sort
     * order, and an account without a balance record gets one first, with the balance of its lines. Inside a lock that
     * holds every one of [accounts], [block] runs in that lock.
     *
     * [block] may call [LockScope.rollback], the gem's `raise ActiveRecord::Rollback` (locking_spec.rb:125-132): it
     * leaves the block, the transaction of the outermost lock rolls back, and that lock returns `null`
     * (docs/deviations.md, X-11). An exception [block] throws rolls the transaction back too and goes through.
     *
     * The transaction must be the outermost: a transaction of [database] already open on the thread is an error, unless
     * [DoubleEntryConfiguration.runningInsideTransactionalFixtures] is set, which allows one and joins it.
     *
     * @throws LockMustBeOutermostTransactionException when a transaction of [database] is already open.
     * @throws LockNotHeldException inside a lock that does not lock all of [accounts].
     * @throws LockDisasterException when a balance record is still missing after it was created.
     * @throws LockWaitTimeoutException when the database gave up waiting for a lock.
     */
    fun <T> lockAccounts(
        vararg accounts: AccountInstance,
        block: LockScope.() -> T,
    ): T? = locking.lockAccounts(accounts.toList(), block)

    /**
     * Transfers [amount] from the account [from] to the account [to] under [code] and returns the two lines it writes,
     * the credit of [from] and the debit of [to]: the gem's `DoubleEntry.transfer` (double_entry.rb:88-90,
     * transfer.rb:16-121). Each line is about [detail], if given. Both accounts are locked, as [lockAccounts] locks
     * them, while their balances are updated and the lines written; the debit's partner is the credit and the credit's
     * the debit.
     *
     * A transfer must be allowed by a transfer rule of [configuration] from [from]'s identifier to [to]'s under [code].
     * [amount] is in the accounts' currency. Each line is checked as `Line#save!` checks it (line.rb:85-88, 156-163):
     * a transfer must not take a positive-only account below zero nor a negative-only account above it, and nothing is
     * written when it would.
     *
     * Without [DoubleEntryConfiguration.jsonMetadata], each value of each key of [metadata] is a row of
     * `double_entry_line_metadata` for the credit and one for the debit, written inside the lock after the lines
     * (transfer.rb:91-94, 123-130), and [lineMetadata] reads them back. A value that is an [Iterable] or an [Array]
     * gives a row per element; a value is stored as its `toString()`. With json metadata, [metadata] is the
     * `metadata` json column of the credit and of the debit, and no row of `double_entry_line_metadata` is written
     * (transfer.rb:112): both lines returned and read back have it as [Line.metadata], whose KDoc gives its shape.
     *
     * @throws TransferIsNegativeException when [amount] is below zero.
     * @throws TransferNotAllowedException when no transfer rule allows the transfer, or [from] and [to] are the same
     *   account in the same scope.
     * @throws MismatchedCurrenciesException when [from] and [to] have different currencies.
     * @throws CurrencyMismatchException when [amount] is in another currency than the accounts.
     * @throws AccountWouldBeSentNegativeException when the transfer would take a positive-only account below zero.
     * @throws AccountWouldBeSentPositiveException when the transfer would take a negative-only account above zero.
     * @throws LockMustBeOutermostTransactionException when a transaction of [database] is already open.
     * @throws LockNotHeldException inside a lock that does not lock both accounts.
     * @throws LockWaitTimeoutException when the database gave up waiting for a lock.
     * @throws IllegalArgumentException when an element of a value of [metadata] is `null`; nothing is written.
     */
    fun transfer(
        amount: Money,
        from: AccountInstance,
        to: AccountInstance,
        code: String? = null,
        detail: Detail? = null,
        metadata: Map<String, Any>? = null,
    ): Pair<Line, Line> = transfers.transfer(amount, from, to, code, detail, metadata)

    /**
     * The line metadata rows of the lines [lineIds], by id: the gem's `DoubleEntry::LineMetadata` (line_metadata.rb)
     * of those lines, the metadata [transfer] writes without json metadata. The rows are read from
     * `double_entry_line_metadata` whatever [DoubleEntryConfiguration.jsonMetadata] says; on a database without that
     * table, the driver's error goes through.
     */
    fun lineMetadata(lineIds: Collection<Long>): List<LineMetadata> = lineMetadataRows.forLines(lineIds)
}
