# Deviations from the gem

Where the port behaves differently from `double_entry` 2.0.2 under `reference/double_entry/`, on purpose. Everything not listed here behaves as the gem does, and the oracle (`oracle/README.md`) checks it.

Each deviation has an id `X-<nn>`, numbered in order and never reused, and four lines:

```markdown
## X-<nn>: <short title>

- Gem: what the gem does, with its `lib/...rb:line` or `spec/...rb:line`.
- Port: what the port does instead.
- Decision: why, and who decided (a decision record, a plan, a person).
- Test: <Class>.<method>, the test that pins the port's behaviour.
```

## X-01: TransferNotAllowed message quotes every non-identifier symbol name

- Gem: `Transfer::Set#find!` raises with `[from, to, code].inspect` (transfer.rb:47). Ruby's `Symbol#inspect` prints operator names (`:+`, `:[]`) and `@`-prefixed names (`:@a`) unquoted, and escapes newlines and `#{`.
- Port: `TransferRules.findOrThrow` writes `:name` for an identifier (optionally ending in `?`, `!` or `=`), `nil` for no code, and otherwise `:"name"` with only `"` and `\` escaped. So `:+` and `:@a` come out as `:"+"` and `:"@a"`; newlines and `#{` are not escaped.
- Decision: the difference shows only for names outside the usual `snake_case` form; a recorded deviation was preferred over full inspect rules (oracle/unknowns.md U-01).
- Test: TransferRuleTest.`the TransferNotAllowed message quotes operator and instance variable names (X-01)`

## X-02: BHD stands in for BTC in the ported examples

- Gem: the suite uses the money gem's BTC, a currency of eight decimal places, for its `btc_test`, `btc_savings`, `btc_store` and `btc_wallet` accounts (spec/support/accounts.rb:10-11, spec/double_entry_spec.rb:246-247) and its amounts (spec/double_entry_spec.rb:286, spec/double_entry/validation/line_check_spec.rb:97, 105, 111; spec/double_entry/line_spec.rb:59).
- Port: `Money` takes its currency from `java.util.Currency`, which has no BTC (`Money.of(1, "BTC")` throws `IllegalArgumentException`). The ported examples use BHD, a currency of three decimal places, in its place, so `Money.of(-10, "BHD")` is written `-0.010` where the gem's -1 BTC is `-0.00000001`.
- Decision: decision 0001 §3 makes the amount a `Long` of minor units with a `java.util.Currency`, not a money library; the port picks BHD as a real currency whose decimal places differ from USD's two, as BTC's do.
- Test: MoneyTest.`BHD with three decimal places stands in for BTC, which java util Currency does not know (X-02)`

## X-03: A deadlock while locking accounts inside the caller's transaction is not restarted

- Gem: `Lock#lock_and_call` grabs the locks inside `with_restart_on_deadlock` in a `restartable_transaction` (locking.rb:126-139): an error whose message matches `/deadlock/i` or `/database is locked/i` publishes `deadlock_restart.double_entry` and runs the lock's transaction again from the start (locking_extensions.rb:9-26), also when the lock runs inside a transaction of the caller (`running_inside_transactional_fixtures`).
- Port: outside a transaction of the caller, `DoubleEntry.lockAccounts` does as the gem does (`LockingExtensions`, the deadlock also known by SQLState 40P01), told as `DoubleEntryEvents.deadlockRestart`. Inside one, allowed by `runningInsideTransactionalFixtures`, a deadlock leaves `lockAccounts` as the driver's error, nothing is told and nothing is run again.
- Decision: on PostgreSQL a deadlock aborts the whole transaction, the caller's included, so running the lock again inside it could not succeed; the caller's transaction is the caller's to retry. Exposed runs every transaction in one attempt (`maxAttempts = 1`), so that it never runs the caller's block a second time.
- Test: LockingTest.`a deadlock while locking is not restarted, the driver's error goes through (X-03)`

## X-04: A line's metadata is null without json metadata, where the gem raises NoMethodError on the generator's tables

- Gem: `has_many :metadata, … unless -> { DoubleEntry.config.json_metadata }` (line.rb:59) tests a lambda, which is always truthy, so the association is never defined (oracle/unknowns.md, U-06). `Line#metadata` is then the lines' `metadata` column: on the generator's tables without `json_metadata` (migration.rb:23-29) the lines have no such column and `line.metadata` raises `NoMethodError` (scenario metadata-table-generator-schema); on the spec's tables (spec/support/schema.rb:24-30) it reads the column, `nil` for a transfer of the metadata table (transfer_spec.rb:74, 142, 192).
- Port: `Line.metadata` is the `metadata` column only with `DoubleEntryConfiguration.jsonMetadata`; without it, the port reads no such column and `Line.metadata` is `null`, on the generator's tables and on any other. The metadata without json metadata is read with `DoubleEntry.lineMetadata`, from `double_entry_line_metadata`. The parity harness accepts the port's `null` in place of the recorded `NoMethodError` for that one scenario (`KnownDeviations`, src/parity/.../Replay.kt).
- Decision: a Kotlin property cannot be missing at run time, and `null` is what the gem's suite expects of a line without json metadata (`be_blank`, transfer_spec.rb:74, 142, 192); oracle/unknowns.md U-06 named this deviation.
- Test: TransferMetadataTableTest.`on the generator's tables without json_metadata, a line's metadata is null (X-04)`

## X-05: The line check's Error on account entry names the account's currency, where the gem's suite expects none

- Gem: line_check_spec.rb:78-80 expects `Error on account #{Account account: savings scope:  currency: }: 100.01 (cached balance) != 100.00 (running balance)`, with an empty currency. `Account::Instance#to_s` writes the account's currency (account.rb:122-124), which an account takes from `Money.default_currency` when it is defined (account.rb:148); the suite's `spec_support.rb` requires spec/support in sorted order, so `accounts.rb` defines the accounts before `money.rb` sets `Money.default_currency = USD` (oracle/unknowns.md, U-02).
- Port: an account defined without a currency takes `DoubleEntryConfiguration.defaultCurrency` when it is defined, USD unless set, so the entry reads `currency: USD}`. The recorder sets the default currency before it defines any account, and line-check-balance-error records the same text.
- Decision: as oracle/unknowns.md U-02 named it, the empty currency is an artefact of the order the gem's suite loads its support files in, not the gem's behaviour; an application that sets its default currency first gets the currency in the entry, as the port writes it.
- Test: validation.LineCheckTest.`a consistency error in account balance, the log should include the error on the account in USD (X-05)`

## X-06: A lock timeout on PostgreSQL raises LockWaitTimeoutException

- Gem: `Locking.lock_accounts` raises `LockWaitTimeout` only for an `ActiveRecord::StatementInvalid` whose message matches `/lock wait timeout/i` (locking.rb:45-50), MySQL's "Lock wait timeout exceeded". PostgreSQL's `lock_timeout` error reads "canceling statement due to lock timeout", which does not match, so on PostgreSQL the gem re-raises the `StatementInvalid`.
- Port: `Locking.lockAccounts` turns a driver error whose message matches the gem's `/lock wait timeout/i` into `LockWaitTimeoutException`, as the gem does, and also any error with SQLState 55P03 (`lock_not_available`, PostgreSQL's lock timeout), the driver's error as its cause. NestedLockingTest pins the regex branch and the timeout of a transfer.
- Decision: the port maps the gem's lock wait timeout to SQLState 55P03, so that the timeout the gem means has its exception on the engine the port targets (decision 0001 §2: the production target is PostgreSQL). X-04 and X-05 are taken by oracle/unknowns.md (U-06, U-02). oracle/unknowns.md U-09 records which of the gem's regex branches fire on PostgreSQL.
- Test: LockingTest.`a lock that waits longer than the lock timeout raises LockWaitTimeoutException (X-06)`

## X-07: A deadlock while creating a missing balance record inside the caller's transaction is not retried

- Gem: `create_missing_account_balances` writes each missing balance record with `create_ignoring_duplicates!` (locking.rb:162-170), which runs the insert again when its error's message matches `/deadlock/i` or `/database is locked/i`, publishing `deadlock_retry.double_entry` (`retry_deadlocks`, locking_extensions.rb:28-36, 57-72), also inside a transaction of the caller.
- Port: outside a transaction of the caller, `Locking.createMissingAccountBalances` does as the gem does (`LockingExtensions.createIgnoringDuplicates`, the deadlock also known by SQLState 40P01), told as `DoubleEntryEvents.deadlockRetry`. Inside one, allowed by `runningInsideTransactionalFixtures`, it still ignores a duplicate, but a deadlock on the insert leaves `lockAccounts` as the driver's error and the insert is not run again.
- Decision: as in X-03, on PostgreSQL a deadlock aborts the whole transaction, so inserting again inside it could not succeed.
- Test: LockingTest.`a deadlock while creating a missing balance record is not retried, the driver's error goes through (X-07)`

## X-08: A transfer amount in another currency than the accounts' raises CurrencyMismatchException

- Gem: `Transfer#create_lines` reckons `credit_balance.balance - amount` inside the lock (transfer.rb:104); the money gem exchanges `amount` into the balance's currency first (money 6.19.0, `Money#-`, `Money#exchange_to`), and with no rate set its default bank raises `Money::Bank::UnknownRate`. The lock's transaction rolls back and no line is written.
- Port: `Transfers.createLines` reckons `creditBalance.balance - amount` with the port's `Money`, whose arithmetic throws `CurrencyMismatchException` for operands of different currencies; `DoubleEntry.transfer` raises it from inside the lock, the transaction rolls back and no line is written.
- Decision: decision 0001 §3 makes `Money` a `Long` of minor units with a `java.util.Currency`, with no bank and no exchange rates, so mixed-currency arithmetic throws `CurrencyMismatchException` (Money.kt); the port keeps the gem's arithmetic, so the error comes from the same place and nothing is written. No oracle scenario covers it (oracle/README.md: an op's amounts are USD Money, and a transfer of BHD Money is not an op yet).
- Test: TransferTest.`an amount in another currency than the accounts' raises CurrencyMismatchException and writes no line`

## X-09: A line without a stored partner has a null partner and pair

- Gem: `Line#partner` is `Line.find(partner_id)` (line.rb:125-127), which raises `ActiveRecord::RecordNotFound` for a line without a partner id or whose partner is not stored, and `Line#pair` calls it (line.rb:129-135).
- Port: `Lines.partnerOf` returns `null` for such a line and `Lines.pairOf` returns `null` too; `Lines.find` returns `null` for an id that is not stored, where `Line.find` raises.
- Decision: the CLAUDE.md rule that the API is idiomatic Kotlin, not a transliteration of Ruby: a lookup that can miss returns a nullable value. No oracle scenario reads the partner of a line without one.
- Test: DoubleEntryLinesTest.`a line without a partner has no partner and no pair`

## X-10: An account instance made without a facade has no balance

- Gem: `Account::Instance#balance` (account.rb:96-98) always reckons the balance through `BalanceCalculator`, on Active Record's global connection, whoever made the instance.
- Port: `AccountInstance.balance` reckons it on the database of the facade that made the instance: `DoubleEntry.account`, and the accounts of the lines `DoubleEntry.lines` reads back. An instance from `Accounts.account` of a configuration alone (`DoubleEntryConfiguration.accounts`) has no database, and its `balance()` throws `IllegalStateException`; `DoubleEntry.balance` gives the balance of any instance.
- Decision: decision 0001 §2 and CLAUDE.md: the caller gives the `Database` and the library keeps no global connection, while the configuration, where the gem's `Account.account` lives, has no database. The port binds the instance to the facade's balance calculator.
- Test: DoubleEntryBalancesTest.`an account instance made without a facade has no balance (X-10)`

## X-11: A lock is rolled back through LockScope.rollback and then returns null

- Gem: the block of `lock_accounts` rolls the lock's transaction back with `raise ActiveRecord::Rollback` (locking_spec.rb:125-132): the exception leaves the block, Active Record's `transaction` rolls back and swallows it (locking.rb:129), and `lock_accounts` returns `nil`, as the outermost lock always does (locking.rb:67-74).
- Port: the block of `DoubleEntry.lockAccounts` has a `LockScope` receiver whose `rollback()` leaves the block; the lock that opened the transaction rolls it back and returns `null`. Otherwise `lockAccounts` returns the block's value, so its type is `T?`. Exposed's own `Transaction.rollback()` is not it: it returns, and the rest of the block runs in a new transaction that commits.
- Decision: rollback goes through a `rollback()` receiver, recorded as a deviation because its shape differs from the gem's; Kotlin has no `ActiveRecord::Rollback` to raise, so the port returns `null`.
- Test: NestedLockingTest.`rollback leaves the block, rolls the lock's transaction back and the lock returns null (X-11)`

## X-12: The last two lines of the line check's Error on line entry are Kotlin's text of the lines

- Gem: `line_error_message` (validation/line_check.rb:97-106) ends each "Error on line" entry with `previous_line.inspect` and `line.inspect` (line_check.rb:102-103): Active Record's `inspect` of the array of none or one previous `Line` and of the line, with every column and the engine's time format.
- Port: `LineCheck.perform` writes there the previous line as a list of none or one, `[]` or `[Line(id=…, …)]`, and the line, `Line(id=…, …)`, as Kotlin's `toString` of the data class `Line` writes them. The rest of the entry, the "Error on account" entries and their order are the gem's.
- Decision: Active Record's `inspect` text depends on the engine and has no counterpart in the port, and the gem's specs match only `/Error on line/` (line_check_spec.rb:58). The recorder writes the two lines as one, `  <masked>` (oracle/README.md, "The masked region of the log"), and the parity harness's `LineCheckExecutor` masks the port's log the same way before it compares.
- Test: validation.LineCheckTest.`an error on a line ends with the previous line and the line as Kotlin writes them (X-12)`
