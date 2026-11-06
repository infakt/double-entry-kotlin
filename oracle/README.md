# The oracle

The oracle records what the gem `double_entry` 2.0.2 under `reference/double_entry/` does, so the Kotlin port can be checked against it. The recorder runs the gem on SQLite; the Kotlin replay harness runs the same scenarios against the port on PostgreSQL and compares. This file is the contract between the two.

This file describes the shapes of `enabled.json`, `index.json`, `inventory.json` and `scenarios/*.json` and what the recorder puts in them.

## Layout

| Path | What | Written by |
| --- | --- | --- |
| `record` | Installs the bundle and runs the recorder. | hand |
| `replay` | Runs the Gradle task `parity`: the Kotlin replay harness. | hand |
| `Gemfile` | The gem by path, Active Record 8, money 6, sqlite3, timecop. | hand |
| `recorder/record.rb` | The recorder. `values.rb`, `database.rb`, `injections.rb`, `ops.rb` and `dsl.rb` beside it. | hand |
| `recorder/scenarios/*.rb` | The scenario definitions. | hand |
| `scenarios/<id>.json` | One scenario: a skeleton `{"id": "<id>"}` until recorded. | recorder |
| `index.json` | `{"scenarios": [{"id", "area", "description", "outcome": "returns"\|"raises"}]}`, one entry per scenario file, sorted by id. | recorder |
| `enabled.json` | `{"areas": {"<area>": "on"\|"off"}}`: per area, whether the replay runs it. Every area of `index.json` is in it. | hand |
| `inventory.json` | The items of `lib/` and the gem's specs recorded or excluded so far (see Inventory). | hand |
| `unknowns.md` | What the recording leaves open for the port. | hand |

## Recording

```sh
oracle/record
```

It needs Ruby ≥ 3.2 with Bundler, and the network the first time (the bundle goes to `oracle/.bundle/`, ignored). It writes `oracle/scenarios/*.json` and `oracle/index.json`, nothing under `reference/`. The scenarios' SQLite databases go to a temporary directory under `TMPDIR`, removed when the recorder ends (see Database).

- Every `scenarios/<id>.json` needs a definition with that id, and every definition a file. Either mismatch stops the recorder before it writes anything, as does a file whose `id` is not its name.
- A re-run writes the same bytes: keys sorted at every level, 2-space JSON, a trailing newline, a frozen clock and no random values.
- Recorded files are generated: never edit them; change the definition and re-record.

### Adding a scenario

1. Add `oracle/scenarios/<id>.json` containing `{"id": "<id>"}`.
2. Add its definition to a file in `oracle/recorder/scenarios/`:

```ruby
scenario 'transfer-rules-find-undefined',
         area: 'transfer-rules',
         source: 'spec/double_entry/transfer_spec.rb:238',
         description: 'find returns nothing for a rule that was not defined.' do
  define_transfer from: 'from', to: 'to', code: 'transfer_code'
  find_transfer from: 'to', to: 'from', code: 'transfer_code'
end
```

- `id` is kebab-case and starts with its area.
- `area` is a key of `enabled.json`'s `areas`.
- Only the last step may raise: a raising step ends the scenario and is its outcome, so a definition with a raising step before the last stops the recorder.
- `source` is one or more `spec/...rb:line`: the gem's examples the scenario characterizes.
- `clock:` (ISO 8601, default `2024-01-01T00:00:00Z`), `schema:` and `events:` are optional. `events: true` (default false) records the gem's notifications as `effects.events` (see Locking extensions).
- `events:` is not written to `input`; it only adds the key to `effects`, so the replay knows a scenario records events by `effects.events`.
- `schema:` is `{shape: 'generator', json_metadata: true|false}` or `{shape: 'spec'}` (see Database), and is checked when the definition loads.
- Each step is an op of the table below, and its arguments are checked when the definition loads.
- Use made-up values only.

### State before each scenario

The recorder resets the gem before every scenario, so no scenario sees another's state:

- `DoubleEntry.config` and `DoubleEntry::Locking.config` are dropped and rebuilt by the gem's own initializers. That gives `json_metadata` false and `running_inside_transactional_fixtures` false.
- `Account.accounts` and `Transfer.transfers` are fresh, empty `Set`s.
- `code_max_length`, `scope_identifier_max_length` and `account_identifier_max_length` are nil.
- `Money.default_currency` is USD, set before any account is defined (see U-02 in `unknowns.md`).
- No `inject_error` is pending: errors a scenario arranged and did not raise stop the recorder at its end.
- The scenario has a fresh SQLite database with its schema (see Database).

The clock is frozen at `input.clock` with Timecop while the steps run, after the database is created.

## Database

Every scenario runs on a database of its own:

- A fresh SQLite file, `<id>.sqlite3` in a temporary directory the recorder makes under `TMPDIR` (`Dir.mktmpdir`) and removes when it ends; never under `reference/`. Every id is unique, and an existing file stops the recorder.
- After each switch the recorder runs `ActiveRecord::Base.establish_connection(adapter: 'sqlite3', database: <file>)`, then `reset_column_information` on `DoubleEntry::Line`, `DoubleEntry::LineMetadata`, `DoubleEntry::AccountBalance` and `DoubleEntry::Validation::LineCheck`, so no model keeps the columns of the last scenario's database.
- Then it creates the schema `input.schema` selects, then freezes the clock at `input.clock` with Timecop and runs the steps.

### Schema shapes

| `input.schema` | Tables |
| --- | --- |
| `{"shape": "generator", "json_metadata": true\|false}` | The install generator's migration, `lib/generators/double_entry/install/templates/migration.rb`. Its ERB is rendered with `migration_version` `"[8.0]"` and the scenario's `json_metadata`, evaluated in a fresh module, and run with `migrate(:up)`. With `json_metadata` true, the lines have a `metadata` json column and there is no `double_entry_line_metadata` table; with false, the other way round. |
| `{"shape": "spec"}` | `reference/double_entry/spec/support/schema.rb`, loaded (read only) as the gem's suite loads it. It has both the lines' `metadata` column and the `double_entry_line_metadata` table (schema.rb:24-30, 48-55), a `users` table, and Active Record's `schema_migrations` and `ar_internal_metadata`. Its lines index on account and code also covers `partner_account` (`lines_account_code_created_at_partner_account_idx`). |

A scenario without `input.schema` runs on the generator shape with `json_metadata` false, the default, and writes no table effects: the configuration, transfer-rules and accounts scenarios have none. The recorder does not set `DoubleEntry.config.json_metadata` from the schema; a scenario that needs it says `set_config`.

### Table effects

A scenario with `input.schema` writes, beside `effects.steps`, the rows of the gem's four tables after the last step:

```json
"effects": {
  "double_entry_account_balances": [],
  "double_entry_line_checks": [],
  "double_entry_line_metadata": [],
  "double_entry_lines": [{"account": "savings", "amount": 1000, "created_at": "2024-01-01T00:00:00Z", "id": 1, ...}],
  "steps": []
}
```

- The keys are the real table names; a table the schema lacks (`double_entry_line_metadata` on the generator shape with `json_metadata` true) is `[]`.
- Rows are ordered by `id`, and each row has every column of the table. Ids are written raw: every database is fresh, so they start at 1.
- A cell is the column's value as the table's model deserializes it, without the model's own readers (`amount` and `balance` are the integer columns, not Money; `errors_found` is a boolean; `key` of line_metadata goes through its SymbolWrapper and is written as a string; a json `metadata` is an object), then written as a plain value (see Values).
- Timestamps are ISO 8601 UTC to the second: `"2024-01-01T00:00:00Z"`. Under the frozen clock they are `input.clock`.

The locking, transfers and lines scenarios (characterize-ledger) are the first to write rows, so they are the first to run the non-empty path of `Database.cell`. Check their first recording by hand before trusting it; the row above is an illustration, not a recording. The metadata scenarios (characterize-validation) are the first on the `spec` shape, so the `spec` branch of `Database.create` first runs with them; check their first recording by hand too.

- `log` of `double_entry_line_checks` is masked as the LineCheck value's is (see Line checks).

### describe_schema

`describe_schema` returns the shape of the four tables the schema has:

```json
{"double_entry_lines": {
  "columns": [{"name": "id", "null": false, "type": "integer"}, {"name": "account", "null": false, "type": "string"}, ...],
  "indexes": [{"columns": ["account", "code", "created_at"], "name": "lines_account_code_created_at_idx", "unique": false}, ...]
}}
```

- Only `double_entry_account_balances`, `double_entry_line_checks`, `double_entry_line_metadata` and `double_entry_lines`; a table the schema lacks is left out.
- `columns` are in table order, `id` first, as `connection.columns` lists them; `null` is whether the column is nullable.
- `indexes` are by name, as `connection.indexes` lists them (the primary key is no index here); `columns` are in index order.
- `type` is the column's Active Record abstract type (`column.type`), normalized so the port's PostgreSQL types compare with SQLite's:

| Active Record type | `type` |
| --- | --- |
| `:string` (varchar) | `string` |
| `:integer`, `:bigint` (ids, references, amounts, balances) | `integer` |
| `:boolean` | `boolean` |
| `:text` | `text` |
| `:json`, `:jsonb` | `json` |
| `:datetime`, `:timestamp` | `datetime` |

Any other type stops the recorder. Lengths, precision (`datetime(6)`), defaults and foreign keys are not part of the value: the two engines differ in them, and the gem does not depend on them.

## Replaying

```sh
oracle/replay
```

It runs `./gradlew --quiet --console=plain parity`, the task of the `parity` source set (`src/parity/kotlin/pl/infakt/doubleentry/parity/`), and is no part of `test` or `check`. The harness runs the scenarios of the areas that are `on` in `enabled.json`, each through its area's executor, and compares outcome and effects by JSON path.

- Output: `parity: N scenarios, M mismatches`, then one line `<scenario> <path>: expected <x>, got <y>` per mismatch, then the counts line again. The script prints the last `parity:` line once more at the end, or `parity: 0 scenarios, 1 mismatches` when Gradle printed none.
- Before the counts, the harness checks `inventory.json` against `index.json` (`InventoryCheck.kt`) and adds a line `inventory <name>: expected <x>, got <y>` for each problem: an item naming a scenario `index.json` does not list, or naming none while neither excluded nor pending; an excluded item without a reason; an unknown status; and, once every area is `on`, an item still `pending`. These lines count as mismatches.
- Exit 1 on any mismatch, on an area that is `on` without an executor, and on an unknown op. The script exits with Gradle's status.
- Where an expected error, or an exception inside an expected event, has `"message": "<engine>"`, only the class is compared (see Errors and the engine-message rule).
- The areas that run SQL (`schema` first) run each scenario on a new, empty database of the tests' PostgreSQL container (`TestDatabase`, src/test), so the replay needs a container engine, as `test` does. `describe_schema` reads the port's tables from `information_schema.columns` and `pg_index` (the catalog `pg_indexes` is a view of, without the primary key) and maps PostgreSQL's `data_type` to the `type` above: `character varying` → `string`, `bigint`/`integer` → `integer`, `boolean`, `text`, `json`/`jsonb` → `json`, `timestamp` with or without time zone → `datetime`; any other type is shown as PostgreSQL's and so mismatches.

## Scenario file

```json
{
  "area": "transfer-rules",
  "description": "...",
  "effects": {
    "steps": [
      {"code": "transfer_code", "from": "from", "to": "to"}
    ]
  },
  "id": "transfer-rules-find-bang-undefined",
  "input": {
    "clock": "2024-01-01T00:00:00Z",
    "steps": [
      {"code": "transfer_code", "from": "from", "op": "define_transfer", "to": "to"},
      {"code": "transfer_code", "from": "to", "op": "find_transfer!", "to": "from"}
    ]
  },
  "outcome": {
    "error": {"class": "DoubleEntry::TransferNotAllowed", "message": "[:to, :from, :transfer_code]"}
  },
  "source": ["spec/double_entry/transfer_spec.rb:261"]
}
```

| Key | What |
| --- | --- |
| `id`, `area` | As defined. Required. |
| `description`, `source` | As defined; `source` is `["spec/...rb:line", ...]`, the gem's examples. Extra keys the schema allows. |
| `input.schema` | Optional: the table shape the steps run on, `{"shape": "generator", "json_metadata": …}` or `{"shape": "spec"}` (see Database). Without it, the generator shape with `json_metadata` false. |
| `input.clock` | The frozen time, ISO 8601 UTC. |
| `input.steps` | `[{"op": ..., <arguments>}]`, in order. |
| `outcome` | The last step's, with exactly one key: `{"result": …}` or `{"error": {"class": "<gem class name>", "message": "…"}}`. |
| `effects.steps` | The results of the steps before the last, in order, as plain values (`[]` for a one-step scenario). None of them raised. |
| `effects.<table>` | Only with `input.schema`: the rows of each of the four tables after the last step (see Table effects). The scenarios without a schema (configuration, transfer-rules, accounts) have only `steps`. |
| `effects.users` | Only in a scenario that used the recorder's `users` table (`restartable_transaction`, `create_ignoring_duplicates`): its rows after the last step, written as the four tables' rows are. |
| `effects.events` | Only in a scenario defined with `events: true`: the gem's notifications, `[{"exception_class", "message", "name"}]`, in the order published (see Locking extensions). |
| `trace`, `environment` | Optional in the schema; no scenario of this stage writes them. |

The replay runs `input.steps` in order, compares each earlier step's result with `effects.steps`, and the last step with `outcome`.

### Values

| Gem | JSON |
| --- | --- |
| `nil` | `null` |
| `true`, `false`, Integer, String | as is |
| Symbol | its name as a string (`:transfer_code` → `"transfer_code"`) |
| Array, Hash | array, object with string keys |
| `Money` | `{"cents": <integer>, "currency": "<ISO code>"}` |
| `Money::Currency` | its ISO code |
| `Account::Instance` | `{"currency", "identifier", "scope_identity"}` |
| `Account` (a definition) | `{"currency", "identifier", "negative_only", "positive_only", "scoped"}`; `positive_only` and `negative_only` are `null` when not given |
| `Transfer` (a rule) | `{"code", "from", "to"}` |
| `Line` | `{"account", "amount", "balance", "code", "detail_id", "detail_type", "id", "partner_account", "partner_id", "partner_scope", "scope"}`: the columns as stored, except `amount` and `balance`, which go through Line's readers and are Money in the account's currency. No timestamps; the table effects have them. |
| `AccountBalance` | `{"account", "balance", "id", "scope"}`: the columns as stored, except `balance`, Money through the reader. |
| `Validation::LineCheck` | `{"errors_found", "id", "last_line_id", "log"}`: the columns as stored, the `log` masked (see Line checks). No timestamps; the table effects have them. |

Anything else stops the recorder: extend `recorder/values.rb` and this table together.

### Errors and the engine-message rule

An error outcome is `{"error": {"class": "<gem class name>", "message": "<message>"}}`.

- `class` is the Ruby class name of the exception, e.g. `DoubleEntry::TransferNotAllowed` or `DoubleEntry::Locking::LockNotHeld`. The port maps each of its exceptions to one of these names.
- An exception raised without a message has Ruby's default, the class name: `DoubleEntry::DuplicateTransfer`.

The library's own messages, and messages of exceptions a scenario injects itself (`raise`, `inject_error`), are recorded and compared exactly.

Errors raised by the database engine or its driver are different. For these the recorder writes the class and `"message": "<engine>"`:

- `ActiveRecord::NotNullViolation`, `ActiveRecord::RecordNotUnique`, `ActiveRecord::StatementInvalid` and its other subclasses;
- `SQLite3::*` exceptions;
- in each case, only when the scenario did not inject them.

Any other exception keeps its message. `NoMethodError` is Ruby's, not the engine's: metadata-table-generator-schema records `Line#metadata` on the generator's tables without json_metadata (U-06, X-04) as `{"class": "NoMethodError", "message": "undefined method 'metadata' for an instance of DoubleEntry::Line"}`, in the wording of the Ruby that recorded it (see Metadata).

The recorder decides by the exception's class, so the rule covers every scenario without a list. lines-null-code-rejected, for one, records `{"error": {"class": "ActiveRecord::NotNullViolation", "message": "<engine>"}}`: SQLite's text, `NOT NULL constraint failed: double_entry_lines.code`, cannot match PostgreSQL's. The replay compares only the gem error class that the port's exception maps to (SQLState 23502 → `ActiveRecord::NotNullViolation`, 23505 → `ActiveRecord::RecordNotUnique`).

The same rule applies to the exception inside a recorded event, written as the event's `exception_class` and `message`: an exception the engine raised keeps its class and gets `"message": "<engine>"`. locking-extensions-ignore-duplicate inserts the same username twice; SQLite's `RecordNotUnique` says `UNIQUE constraint failed: users.username`, which PostgreSQL's `duplicate key value violates unique constraint` cannot match, so the event is recorded as:

```json
"events": [
  {"exception_class": "ActiveRecord::RecordNotUnique", "message": "<engine>", "name": "duplicate_ignore.double_entry"}
]
```

An exception `inject_error` raised is the scenario's own, so its message stays exact even inside an event and even though its class is `ActiveRecord::StatementInvalid`. locking-extensions-deadlock-retry-postgres records each retry as:

```json
{"exception_class": "ActiveRecord::StatementInvalid", "message": "PG::Error: ERROR:  deadlock detected", "name": "deadlock_retry.double_entry"}
```

The same holds for the outcome of an injected error: nested-locking-other-statement-error ends with `{"class": "ActiveRecord::StatementInvalid", "message": "some other error"}`. nested-locking-lock-wait-timeout injects `lock wait timeout` as given; the gem turns it into `DoubleEntry::Locking::LockWaitTimeout`, which has its class name as the message (U-03).

The reason is a technical assumption of the plan: the gem is recorded on SQLite and the port runs on PostgreSQL, and the two engines word the same violation differently.

## Ops

Identifiers and codes are strings in the file; the recorder hands them to the gem as symbols, as an application's configuration writes them.

| Op | Arguments | Drives | Value |
| --- | --- | --- | --- |
| `set_config` | `key`, `value` | `DoubleEntry.configure { \|c\| c.<key> = value }` (`Locking.configure` for the fixtures flag) | `null` |
| `get_config` | `key` | `DoubleEntry.config.<key>` (`Locking.config` for the fixtures flag) | the setting |
| `define_account` | `identifier`; optional `scope`, `positive_only`, `negative_only`, `currency` | `config.define_accounts { \|a\| a.define(...) }` | the `Account` |
| `define_transfer` | `from`, `to`, `code` | `config.define_transfers { \|t\| t.define(...) }` | the `Transfer` |
| `find_transfer` | `from`, `to`, `code` | `DoubleEntry.config.transfers.find(from, to, code)` | the `Transfer` or `null` |
| `find_transfer!` | `from`, `to`, `code` | `DoubleEntry.config.transfers.find!(from, to, code)` | the `Transfer` |
| `account` | an account reference | `DoubleEntry.account(identifier, scope:, scope_identity:)` | the `Account::Instance` |
| `account_attr` | an account reference, `attr` | `DoubleEntry.account(...).<attr>`; `attr` is `identifier`, `scope_identity`, `currency`, `scoped?`, `positive_only` or `negative_only` | the attribute |
| `account_to_s` | an account reference | `DoubleEntry.account(...).to_s` | the string |
| `account_currency` | `identifier` | `DoubleEntry::Account.currency(identifier)` | the definition's currency code, or `null` for an identifier no account has |
| `sort_accounts` | `accounts`: `[reference, ...]` | `accounts.map { DoubleEntry.account(...) }.sort` (`Instance#<=>`) | the instances, sorted |
| `accounts_equal` | `left`, `right`: references | `left == right` | a boolean |
| `hash_equal` | `left`, `right`: references | `left.hash == right.hash` | a boolean |
| `describe_schema` | none | the database's tables (see describe_schema) | `{"<table>": {"columns", "indexes"}}` |
| `lock_accounts` | `accounts`: `[reference, ...]`; `steps`: `[step, ...]` | `DoubleEntry.lock_accounts(*accounts) { <steps> }` | `null` |
| `in_transaction` | `steps`: `[step, ...]` | `DoubleEntry::AccountBalance.transaction { <steps> }`, the regular transaction of locking_spec.rb:76-89 | `null` |
| `raise` | `message` | `raise RuntimeError, message`, marked as injected | never returns |
| `transfer` | `amount`, `from`, `to`: references; optional `code`, `detail`, `currency`, `metadata` | `DoubleEntry.transfer(Money.new(amount, currency), from:, to:, code:, detail:, metadata:)` | `[credit, debit]`, two `Line`s |
| `seed_line` | `account`, `partner_account`: references; `amount`, `balance`; optional `code` | `DoubleEntry::Line.create!(...)`, as locking_spec.rb:52-65 | the `Line` |
| `line_save` | `account`: a reference; `balance`; optional `partner_account`, `amount`, `code` | `DoubleEntry::Line.new(...).save`, as line_spec.rb:67-85 | a boolean |
| `line_attr` | `line`: an id; `attr` or `column`, exactly one | `Line.find(line).<attr>`, or the raw column `Line.find(line)[column]` | the attribute |
| `line_partner` | `line`: an id | `Line.find(line).partner` | the `Line` |
| `line_pair` | `line`: an id | `Line.find(line).pair` | `[credit, debit]`, two `Line`s |
| `account_balance` | an account reference | `DoubleEntry::AccountBalance.find_by_account(DoubleEntry.account(...))` | the `AccountBalance` or `null` |
| `lines_scope` | `scope`: `credits` or `debits` | `DoubleEntry::Line.<scope>.order(:id)` | the `Line`s |
| `travel` | `to`: ISO 8601 UTC | `Timecop.freeze(Time.iso8601(to))`, for the steps after it | `null` |
| `balance` | `account`: a reference, or `identifier` with an optional `scope`; optional `at`, `from`, `to`, `code`, `codes` | `DoubleEntry.account(...).balance(at:, from:, to:, code:, codes:)`, or `DoubleEntry.balance(identifier, scope:, ...)` | the `Money` |
| `account_balance_record` | an account reference | `DoubleEntry::AccountBalance.find_by_account(DoubleEntry.account(...))&.balance` | the `Money` or `null` |
| `scopes_with_minimum_balance` | `amount`, `identifier` | `DoubleEntry::AccountBalance.scopes_with_minimum_balance_for_account(Money.new(amount), identifier)` | the scopes, sorted |
| `rollback` | none | `raise ActiveRecord::Rollback`, as locking_spec.rb:128 | never returns |
| `inject_error` | `target`, `message`, `times`; optional `error` | arranges `times` exceptions of `error` with `message`, marked as injected, for the next calls to `target` (see Locking extensions) | `null` |
| `restartable_transaction` | `steps`: `[step, ...]` | `UserRecord.restartable_transaction { <steps> }`, as locking_extensions_spec.rb:14 | `null` |
| `create_ignoring_duplicates` | `username` | `UserRecord.create_ignoring_duplicates!(username:)` | `null` |
| `line_metadata_count` | none | `DoubleEntry::LineMetadata.count` | the integer |
| `line_metadata_of` | `line`: an id | `Line.find(line).metadata`, the reader | the json metadata, an object, or `null` |
| `line_check` | `fixer`: `true` or `false` | `DoubleEntry::Validation::LineCheck.perform!(fixer: AccountFixer.new)`, or `fixer: nil` | the `LineCheck` or `null` |
| `create_line_check` | `last_line_id`, `errors_found`, `log` | `LineCheck.create!(last_line_id:, errors_found:, log:)`, as line_check_spec.rb:11-17 | the `LineCheck` |
| `last_line_id_checked` | none | `LineCheck.last_line_id_checked` | the integer |
| `sql_update` | `table`, `delta` | `<model>.order(:id).limit(1).update_all("balance = balance + <delta>")`, as line_check_spec.rb:55 and 74 | `null` |

- Config keys: `code_max_length`, `account_identifier_max_length`, `scope_identifier_max_length`, `json_metadata` and `running_inside_transactional_fixtures` (the last on `DoubleEntry::Locking`).
- `define_account`'s `scope` is the account's `scope_identifier`, as the gem's specs write it:
  - `none` (the default): unscoped;
  - `identity`: `->(value) { value }` (account_spec.rb:4);
  - `user_id`: `->(user) { user.id }` (spec/support/accounts.rb:6);
  - `user_only`: raises `'not a User'` unless the scope's class is named `User`, then `user.id` (double_entry_spec.rb:355).
- `currency` is passed to the gem as given, a string; without it the account takes `Money.default_currency`.
- `find_transfer` and `find_transfer!` pass account stand-ins that carry only an `identifier`, as transfer_spec.rb:219-220 does.
- An account reference is `{"identifier": …}` with at most one of `scope` and `scope_identity`; `account`, `account_attr` and `account_to_s` take its keys flat in the step, the other ops nest it. The recorder passes only the keys the reference gives:
  - `scope` is a string, passed as is (for the `identity` scope), or `{"user": <integer>}`, a `User` with that id: a top-level `User = Struct.new(:id)` of the recorder, so `user_id` reads its id and `user_only` accepts it. Its `to_s` is Ruby's Struct text, so `account_to_s` scenarios use string scopes.
  - `scope_identity` is a string, passed as is.
  - The account is defined by an earlier `define_account`: where account_spec.rb builds `Account::Instance.new` on an Account it makes itself, the scenarios go through `DoubleEntry.account`.
- `hash_equal` records only whether the two hashes are equal, never a hash: Ruby seeds `String#hash` per process (U-11).
- `account_balance` and `account_balance_record` take their reference flat in the step, like `account`; `transfer`, `seed_line`, `line_save`, `lock_accounts` and `balance`'s `account` nest theirs.

The ledger ops (locks, transactions, transfers, lines, account balances):

- Amounts and balances are integer cents, handed to the gem as `Money.new(cents)`, in `Money.default_currency` (USD). Line and AccountBalance only keep `fractional`, so a line of a BHD account stores the cents as given. A transfer between two BHD accounts gives `currency: "BHD"`: its amount is then `Money.new(cents, "BHD")`, since the gem adds it to the accounts' BHD balances (transfer.rb:104-105).
- `lock_accounts` and `in_transaction` run their nested `steps` inside the block, in order. The nested steps' results are not recorded. A nested step that raises leaves the block, so the gem rolls the transaction back, and the error is the outer step's outcome; the outer step must then be the scenario's last. In a definition the nested steps are a block: `lock_accounts(accounts: [{ identifier: 'account_a', scope: '1' }]) { transfer ... }`.
- `raise` raises a `RuntimeError` with the message, marked as injected, so its message is recorded exactly (see Errors).
- `transfer`'s `code` is passed only when given: without it the gem looks up a rule with a nil code (double_entry_spec.rb:144). `detail` is `{"invoice": <integer>}`: a persisted row of a table the recorder adds on first use, `oracle_invoices`, mapped by a top-level `Invoice` model, since the generator schema has no model a line could point at. The line then has `detail_type` `"Invoice"` and `detail_id` the integer. That table is none of the gem's four, so neither `describe_schema` nor the table effects show it.
- `seed_line` and `line_save` pass only the attributes the step gives. `code` may be `null`: Line's `code=` stores nil, and the NOT NULL constraint of the column rejects it (line_spec.rb:32-41).
- A line is named by its id. Every database is fresh, so a transfer's credit and debit are 1 and 2 when no line came before.
- `line_attr`'s `attr` is one of Line's readers: `amount`, `balance`, `code`, `account`, `partner_account`, `currency`, `increase?`, `decrease?`. Its `column` is a raw column, as `line[:column]` reads it: `id`, `account`, `scope`, `code`, `amount`, `balance`, `partner_id`, `partner_account`, `partner_scope`, `detail_type`, `detail_id`.
- `lines_scope` lists by id. `credits` selects a positive amount and `debits` a negative one, the other way round from Transfer's names (U-07).

The balance ops (clock moves, balances, balance records):

- `travel` freezes the clock at `to` for the steps after it, until the next `travel`, as the gem's specs do with `Timecop.freeze` (double_entry_spec.rb:261-287). The scenario still starts at `input.clock`, and the clock is back at it when the scenario ends. A line or balance record written after a `travel` has that time as its `created_at`.
- Every time is absolute ISO 8601 UTC, ending in `Z`, in the file: a definition computes it from the clock (the timeline of `recorder/scenarios/balances.rb`), so the replay reads no relative form.
- `balance` takes exactly one of `account`, a nested reference (`Account::Instance#balance`), and `identifier` with an optional `scope` flat in the step (`DoubleEntry.balance`, which finds the account with the same options, double_entry.rb:142-145). Only the options the step gives are passed: `at`, `from` and `to` as `Time`s; `code` as a symbol; `codes` as symbols.
- `account_balance_record` is the balance of the account's record, Money in the account's currency, or `null` when the account has no record; `account_balance` gives the whole record.
- `scopes_with_minimum_balance` passes `amount` as `Money.new(cents)` and `identifier` as a symbol. The gem plucks the scopes in no order, so the recorder sorts them: a `null` scope first, then by text (U-14).

### Locking extensions

The locking ops (rollbacks, injected errors, the extensions of `lib/active_record/locking_extensions.rb`):

- `rollback` leaves the enclosing transaction; Active Record rolls it back and does not re-raise, so the `lock_accounts` around it returns `null`. Outside a transaction it is the step's error.
- `inject_error`'s `target` is `with_restart_on_deadlock` or `create!`; `error` is `ActiveRecord::StatementInvalid` (the default) or `ActiveRecord::RestartTransaction`; `times` is a positive integer. The errors are raised by the next `times` calls to the target, from the step on:
  - `with_restart_on_deadlock` (`DoubleEntry::AccountBalance`'s, which `lock_accounts` calls around `grab_locks`, locking.rb:130): the error is raised from the block the method wraps, not by the method, so the gem's own rescue (locking_extensions.rb:19-26) still matches the message, publishes `deadlock_restart` and raises `RestartTransaction`, or re-raises. locking_spec.rb:155 and 168 stub the method instead, which skips that rescue.
  - `create!` (`UserRecord`'s): the error is raised before the insert, as locking_extensions_spec.rb:10 and 72 make `User.create!` raise; once they are used up, the real `create!` inserts the row.
  - Every injected error must be raised by the end of the scenario, or the recorder stops.
- `restartable_transaction` and `create_ignoring_duplicates` run on `users`, a table the recorder adds on first use, mapped by a top-level `UserRecord` model (`User` is the scope Struct). It has spec/support/schema.rb:58-63's shape: `username`, NOT NULL with a unique index `index_users_on_username`, and the timestamps. It is created in the scenario's own database, never under `reference/`; the spec shape already has it. It is none of the gem's four tables, so neither `describe_schema` nor the four tables' effects show it; a scenario that used it writes its rows as `effects.users`.
- `restartable_transaction` runs its nested steps as `lock_accounts` does.
- `create_ignoring_duplicates`'s value is `null`: the gem returns the record, or what the ignored branch's `publish` gives (locking_extensions.rb:49), and neither is behaviour.
- `events: true` subscribes to `ActiveSupport::Notifications` for `/\.double_entry\z/` while the steps run. Each notification is `{"exception_class", "message", "name"}`: the full event name (`deadlock_restart.double_entry`, `deadlock_retry.double_entry`, `duplicate_ignore.double_entry`), and the class and message of the payload's `exception`, the message under the engine-message rule (see Errors). An event without an exception stops the recorder.
- The concurrent examples of locking_spec.rb:181-213 have no scenario: SQLite cannot run them (the spec skips them with `DB=sqlite`), and the concurrency stage covers them on PostgreSQL.

### Metadata

The metadata ops (a transfer's metadata, line_metadata, the lines' metadata column):

- `transfer`'s `metadata` is `{"<key>": "<value>" | ["<value>", ...]}`, as transfer_spec.rb:84 and 152 give it. The keys reach the gem as symbols, sorted: the file's order, and the order `create_line_metadata` creates the rows in (transfer.rb:123-129), so the ids of `double_entry_line_metadata` follow it. A key with an array has one row per value and line.
- The metadata scenarios run on `{"shape": "spec"}`, which has both the lines' `metadata` column and the `double_entry_line_metadata` table, as the gem's suite does. metadata-json sets `json_metadata` with `set_config`; metadata-table leaves the default, false.
- `line_metadata_of` reads `Line#metadata`. The `has_many :metadata` of line.rb:59 is never defined (U-06), so on the spec shape it is the json column: an object with string keys, or `null`. On the generator shape without json_metadata the lines have no such column, and the gem raises `NoMethodError`: metadata-table-generator-schema records it, message and all (X-04). Its wording depends on the Ruby version (3.4 quotes `'metadata'`, earlier versions `` `metadata' ``), so a re-recording on another Ruby changes those bytes.
- `line_metadata_count` counts the table's rows; the rows themselves are `effects.double_entry_line_metadata`, `key` written as a string.

### Line checks

The validation ops (`lib/double_entry/validation/`):

- The line-check and account-fixer scenarios run on the generator shape without json_metadata. line-check runs `line_check` with `fixer: false`, account-fixer with `fixer: true`; the check itself, its `errors_found` and its `log` are the same either way, only the balances after it differ.
- `create_line_check` and `travel` make the checks of line_check_spec.rb:9-19; `last_line_id_checked` is then the `last_line_id` of the newest by `created_at`, or 0 without checks.
- `sql_update`'s `table` is `double_entry_lines` or `double_entry_account_balances`, and `delta` an integer in cents: it adds `delta` to the `balance` of the table's first row by id, past the models, as the spec corrupts a balance. Its value is `null`, not `update_all`'s count.
- The BTC accounts of line_check_spec.rb:96-120 are BHD ones (X-02): `bhd_test` and `bhd_savings`, code `bhd_test_transfer`.

**The masked region of the log.** The 'Error on line' entry that `running_balance_correct?` adds to the log (`line_error_message`, line_check.rb:97-106) ends with two lines, the `inspect` of the previous line and of the line (line_check.rb:102-103). They are Active Record's `inspect` text, with the engine's time formats and every column; the port cannot write them and the gem's specs match only `/Error on line/` (line_check_spec.rb:58). So the recorder replaces those two lines with one, `  <masked>` (two spaces, then `<masked>`):

```text
*********************************
Error on line #1: balance:-99.99 != 0.00 + -100.00
*********************************
  <masked>

```

- The rest of the entry, the 'Error on account' entries (line_check.rb:113-117) and the order of the entries are recorded exactly.
- The mask applies to the `log` of a LineCheck value and to the `log` column of `effects.double_entry_line_checks` alike.
- An 'Error on line' entry whose two lines do not match stops the recorder, so no inspect text is ever recorded.
- The port writes `  <masked>` in their place, or the replay masks the port's log the same way. The line-check stage records the mask as a deviation in `docs/deviations.md`.
- The example above shows the shape of an entry, not a recording.

## Inventory

```json
{
  "reference": {"path": "reference/double_entry", "commit": "<reference/SOURCE_SHA>"},
  "items": [
    {"id": "<kind>:<name>", "kind": "api|error|config|table|rule|test|other", "summary": "…",
     "source": "lib/...rb:line", "area": "<area>", "status": "verified|inferred",
     "scenarios": ["<id>", ...], "excluded": "<reason>"}
  ]
}
```

- Every item has a non-empty `scenarios` or a non-empty `excluded` reason.
- `status` says how the item is known: `verified` when a scenario records it or the gem's specs assert it, `inferred` when it is read from the code only.
- `source` and `area` are extra keys the schema allows. `source` is `lib/...rb:line`, or `spec/...rb:line` for a `test` item.
- Every item names its scenarios or is excluded with a reason; no item is pending.
- One item per line, so a diff shows what was added.
