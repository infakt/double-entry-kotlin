# Technical unknowns

These are behaviours of the gem that the recording shows but does not settle for the port. Each one says what the gem does, why it matters and where it surfaces. A port stage that settles one records the decision in `docs/deviations.md` (X-<nn>) or follows the gem, and says so here.

## U-01: TransferNotAllowed carries Ruby's inspect of an array of symbols

`Transfer::Set#find!` raises `TransferNotAllowed` with the message `[from_account.identifier, to_account.identifier, code].inspect` (transfer.rb:47). Recorded: `[:to, :from, :transfer_code]` (transfer-rules-find-bang-undefined).

The port has to produce Ruby's inspect format:
- `:name` for a plain symbol;
- `:"two words"` for a symbol that is not a plain identifier;
- `nil` for a missing code;
- `", "` between the elements.

The `from account and to account are identical` message of transfer.rb:86 is plain text and has no such issue.

Settled by transfer-rules: the port follows the gem for identifier names and deviates as X-01 for other names. `TransferRules.findOrThrow` writes the three forms and escapes `"` and `\` in a quoted name; operator symbols such as `:+` and `:@a` are not special-cased, and newlines and `#{` are not escaped. Recorded as deviation X-01 in docs/deviations.md (TransferRuleTest).

## U-02: the default currency depends on load order (becomes X-05 in line-check)

An account takes `Money.default_currency` when it is defined (account.rb:148), not when it is used. In the gem's suite, `spec/spec_support.rb` requires `spec/support/**/*.rb` in sorted order, so `accounts.rb` defines the suite's accounts before `money.rb` sets `Money.default_currency = USD`. Those accounts carry whatever the money gem's unset default is at that moment. That is why line-check's log text in the gem's suite shows an empty currency in `#{Account account: … currency: }`.

The recorder sets `Money.default_currency = USD` before any account is defined, in every scenario. So recorded accounts always carry USD unless a scenario gives a currency. Line-check's characterization has to treat the gem suite's empty currency as a load-order artefact, X-05, not as behaviour.

Settled by line-check: deviation X-05 in docs/deviations.md. An account defined without a currency takes the configured default when it is defined, so the line check's entry names it (validation.LineCheckTest, `a consistency error in account balance, the log should include the error on the account in USD (X-05)`; AccountTest, `the default currency is read when an account is defined`).

## U-03: an error raised without a message has the class name as its message

`fail DuplicateTransfer` (transfer.rb:34) and the other bare `fail`s have Ruby's default message, the class name: `DoubleEntry::DuplicateTransfer` (transfer-rules-define-duplicate). The same holds for:
- `DuplicateAccount` (account.rb:36);
- `TransferIsNegative` (transfer.rb:17);
- `LockMustBeOutermostTransaction` (locking.rb:118);
- `LockDisaster` (locking.rb:72);
- `LockWaitTimeout` (locking.rb:47).

The replay compares messages exactly. So the port either gives these exceptions the gem's class name as the message, or the replay maps it.

Settled by transfer-rules: the replay maps it. The port throws these exceptions without a message, and `errorOf` (Replay.kt) writes the gem class name in its place (ErrorNamesTest).

## U-04: identifiers and codes are symbols in the gem, strings in the files

The gem keys accounts and transfer rules by whatever it is given; an application writes symbols. The recorder passes every identifier and code as a symbol. transfer_spec.rb:10 and 17 pass the codes as strings, but `length` and the interpolation in the TransferCodeTooLongError message read the same for a symbol. Mixing a string and a symbol for the same name would make two different keys in the gem; no scenario does it.

This section only states a constraint, and the port follows it. The port keys accounts and transfer rules by `String`, so there is nothing that could be a symbol. The replay hands the file's strings to the port as they are.

## U-05: an account keeps its currency as given

`Account.new` stores `args[:currency] || Money.default_currency` (account.rb:148). A given currency stays a String (`'BTC'` in spec/support/accounts.rb:10); the default is a `Money::Currency`. Both are recorded as the ISO code. `Transfer#process` then compares the two currencies with `!=` (transfer.rb:88), with `to_account.currency` as the receiver, so the two directions call different `==` methods: `String#==` when the String account is the to side, `Money::Currency#==` when it is the from side.

Settled by oracle-gaps: transfers-explicit-currency-to-default (the String on the from side) and transfers-default-currency-to-explicit (the String on the to side). The gem creates the credit and debit lines in both directions and raises `MismatchedCurrencies` in neither: `'USD' != Money::Currency USD` is false for the same code, whichever side is the receiver. The prediction made before recording, that the to-explicit direction raises, was wrong. The port compares `java.util.Currency` objects (Transfers.kt), raises in neither direction, and so follows the gem; the replay of both scenarios has no mismatch.

## U-06: Line's metadata association is never defined

`has_many :metadata, … unless -> { DoubleEntry.config.json_metadata }` (line.rb:59) tests a lambda, which is always truthy. So the association is never defined, whatever `json_metadata` says. In table mode on the generator schema, `line.metadata` then raises NoMethodError, and the port returns null instead (X-04). On the spec schema, a `metadata` column answers. Characterize-validation records both.

Settled by metadata-table: deviation X-04 in docs/deviations.md. Without json metadata a line's metadata is null (TransferMetadataTableTest, `on the generator's tables without json_metadata, a line's metadata is null (X-04)`). The replay's executor reads that null where the gem raises NoMethodError (MetadataTableExecutorTest, `on the generator's tables the port reads a null metadata where the gem raises NoMethodError (X-04)`).

## U-07: Line.credits and Line.debits are named the other way round

`Line.credits` selects `amount > 0` and `Line.debits` selects `amount < 0` (line.rb:60-61). Yet `Transfer#create_lines` calls the negative line `credit` and the positive one `debit` (transfer.rb:107). The port keeps the scope semantics, the amount sign, whatever it names them.

Settled: the port follows the gem. Its credits are the lines with a positive amount and its debits those with a negative one (DoubleEntryLinesTest, `credits lists the lines of a positive amount and debits those of a negative one (U-07)`). The replay of lines-credits-debits has no mismatch (LinesExecutorTest).

## U-08: MissingAccountError is never raised

`MissingAccountError` (errors.rb:15) is defined but nothing in `lib/` raises it. `Line#account=` raises a bare RuntimeError `'Missing Account'` (line.rb:102), but `DoubleEntry.account` raises `UnknownAccount` before that line is reached. The inventory excludes MissingAccountError from scenarios. The port may still declare it, to keep the error hierarchy complete.

Settled: the port declares `MissingAccountException` and, like the gem, raises it nowhere (DoubleEntryException.kt). ErrorNamesTest pins its gem name, `DoubleEntry::MissingAccountError`.

## U-09: the recorder's engine and the port's engine differ

The gem is recorded on SQLite and the port runs on PostgreSQL. Engine and driver errors are therefore recorded as `"message": "<engine>"` (see `README.md`). Which of the gem's message-regex branches can fire on PostgreSQL is a question for characterize-locking:
- `/lock wait timeout/` (locking.rb:46);
- `/deadlock/` and `/database is locked/` (locking_extensions.rb:20, 63);
- `/duplicate/` and `/ConstraintException/` (locking_extensions.rb:48).

What characterize-locking records: the deadlock and lock-wait branches are driven by `inject_error` with the spec's messages (locking_extensions_spec.rb:4-6, locking_spec.rb:153, 169), so they show the gem's regexes, not what an engine says. Only the duplicate branch runs on a real engine error: SQLite's `RecordNotUnique`, whose text carries `SQLite3::ConstraintException`, recorded as `"<engine>"`. Read against PostgreSQL's own texts (not recorded):
- a deadlock (SQLState 40P01, `deadlock detected`) matches `/deadlock/`;
- a lock timeout (55P03, `canceling statement due to lock timeout`) does not match `/lock wait timeout/`, MySQL's wording; the port decides by SQLState (X-06 in docs/deviations.md);
- a unique violation (23505, `duplicate key value violates unique constraint`) matches `/duplicate/`.

Settled for the port's engine by locking and locking-extensions. The port takes the gem's regexes as they are. It also tells a deadlock and a duplicate by their SQLState, 40P01 and 23505, anywhere in the error's causes (LockingExtensionsTest, `a deadlock and a duplicate are known by PostgreSQL's SQLState too, as the cause of another error`). It turns a lock timeout, 55P03, into LockWaitTimeoutException (deviation X-06; LockingTest, `a lock that waits longer than the lock timeout raises LockWaitTimeoutException (X-06)`).

## U-10: the recorder bypasses the Railtie and resets by dropping memoised configuration

The Railtie's `after_initialize` hook (double_entry.rb:25-33) never fires without a Rails application. So the recorder requires `double_entry/line_metadata` and `double_entry/line` itself, both regardless of `json_metadata`, as the gem's `spec/spec_support.rb` does.

Between scenarios, the recorder drops the memoised `DoubleEntry.config` and `Locking.config`, so the gem's own initializers give the defaults that configuration-defaults records. It does not assign the defaults itself.

This section only states a constraint, on how the recorder runs the gem, and the recorder follows it. The port has no Railtie. Its defaults are those of a new `DoubleEntryConfiguration`, and the replay of configuration-defaults has no mismatch (ConfigurationExecutorTest).

## U-11: an account instance's hash is Ruby's, seeded per process

`Account::Instance#hash` (account.rb:114) is the `String#hash` or `Symbol#hash` of `"scope_identity:identifier"` or of the identifier. Ruby seeds these per process, so no hash value is behaviour. accounts-equality-and-hash records only whether two hashes are equal. The port needs equal hashes for equal instances (the scope identity and identifier for a scoped account, the identifier alone for an unscoped one); its own values may be anything.

This section only states a constraint, and the port follows it. Equal instances have equal hashes (AccountTest, `an account instance is hashable`), and the replay compares only whether two hashes are equal (AccountsExecutorTest, `accounts_equal and hash_equal compare two references`).

## U-12: a blank scope finds the unscoped account, a blank scope identity is kept

`Account.account` asks `present?` of `scope` and `scope_identity` (account.rb:17), so `""` asks for an unscoped account. The instance then keeps a given `scope_identity` as is (account.rb:80): `scope_identity: ""` yields an unscoped instance whose scope identity is `""`, not nil (accounts-blank-scope-is-unscoped). The port has to treat a blank string as no scope when finding, and keep it when it is the given scope identity.

Settled: the port follows the gem (AccountTest, `a blank scope or scope identity counts as none, and a given blank scope identity is kept`). The replay of accounts-blank-scope-is-unscoped has no mismatch.

## U-13: describe_schema compares normalized types only

The recorder's SQLite and the port's PostgreSQL name column types differently (`varchar` and `character varying`, `datetime(6)` and `timestamp(6) without time zone`, `json` and `jsonb`). describe_schema writes the Active Record abstract type folded into six names (see "describe_schema" in `README.md`), and leaves out lengths, precision, defaults and foreign keys. The schema stage decides whether the port's tables need any of these beyond the gem's.

Settled by schema-log-unknowns: the port follows the migration as Active Record runs it on PostgreSQL, beyond the six types:
- a `t.string` is `character varying` without a length (the port had `varchar(255)`, MySQL's, and now has none);
- the `t.timestamps` are `timestamp` without time zone with a precision of 6;
- the ids are `bigserial`, the only columns with a default;
- the nullability is the migration's;
- there are no foreign keys.

DoubleEntrySchemaTest reads these from `information_schema` in both metadata modes (`with json metadata, the columns have the migration's lengths, precisions and defaults`, `without json metadata, …`, `the tables have no foreign key, as the migration's references have none`). docs/schema.md writes the same DDL, and SchemaDocTest checks it.

## U-14: scopes_with_minimum_balance_for_account returns its scopes in no order

`AccountBalance.scopes_with_minimum_balance_for_account` plucks the `scope` column of the matching records with no `ORDER BY` (account_balance.rb:50-52). The order is whatever the engine returns: on SQLite usually by id, on PostgreSQL not promised. So the recorder sorts the scopes, a `null` scope first, then by text, and the `scopes_with_minimum_balance` op compares them sorted (account-balances-min-balance-below, -equal, -above). The port's method may return them in any order; the account-balances stage decides whether it sorts them or only the replay does.

Settled by account-balances: the replay sorts the scopes as the recorder does (AccountBalancesExecutor). `AccountBalances.scopesWithMinimumBalanceForAccount` gives them in the order of the records' ids (AccountBalances.kt:72), an order the gem does not promise. AccountBalanceTest pins it with `the scopes are those of the identifier's records at or above the minimum, in the order of the records`.

## U-15: balance times are compared as stored on SQLite, as timestamps on PostgreSQL

`BalanceCalculator` filters lines with `created_at <= at` and `created_at BETWEEN from AND to` (balance_calculator.rb:90-91). On SQLite, Active Record stores `created_at` as text, `YYYY-MM-DD HH:MM:SS`, with `.ffffff` only when the microseconds are not zero, and quotes the bound times the same way, so the comparison is between strings. Under the frozen clock every time has zero microseconds, so stored and bound times have the same form and compare as times would. PostgreSQL compares timestamps. The scenarios put a bound exactly on the time of a line, which counts in both forms (balances-at, balances-between). Until the oracle-gaps stage, no time with microseconds was recorded.

Settled by oracle-gaps: balances-at-microseconds and balances-between-microseconds create lines at `2024-01-01T00:00:00.123456Z` and `2024-01-01T00:00:01.654321Z` (the `travel` op takes fractional ISO 8601, so neither the recorder nor BalancesExecutor changed) and put `at`, `from` and `to` a microsecond before, at and after each line, on the whole seconds around them, and on a bound with five fraction digits. The gem's results are exact to the microsecond (at `.123455` the balance is 0, at `.123456` it is 10000, at `1.654321` it is 15000), and the replay of both scenarios has no mismatch, so SQLite's text comparison and PostgreSQL's timestamp comparison agree on these bounds. The table effects still write `created_at` to the second, on both sides.
