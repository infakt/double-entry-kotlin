# Schema

The tables of the library on PostgreSQL, as `DoubleEntrySchema.create(database, jsonMetadata)` makes them: the gem's install migration (`reference/double_entry/lib/generators/double_entry/install/templates/migration.rb`). Use this DDL for a migration of your own (Flyway, Liquibase…) in place of `DoubleEntrySchema.create`.

There are two shapes, chosen by `DoubleEntryConfiguration.jsonMetadata`, which must match the shape of the database:

- `jsonMetadata = true`: the lines have a `jsonb` `metadata` column; there is no `double_entry_line_metadata` table.
- `jsonMetadata = false` (the default): the lines have no `metadata` column; a transfer's metadata goes to `double_entry_line_metadata`, a row per key and value.

Run the SQL of [Both shapes](#both-shapes), then that of the shape you use. `SchemaDocTest` runs the SQL of this file and checks that it gives the same tables, columns and indexes as `DoubleEntrySchema.create`, for each shape.

## Both shapes

```sql
CREATE TABLE double_entry_account_balances (
    id bigserial PRIMARY KEY,
    account varchar NOT NULL,
    scope varchar NULL,
    balance bigint NOT NULL,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL
);
CREATE INDEX index_account_balances_on_account ON double_entry_account_balances (account);
CREATE UNIQUE INDEX index_account_balances_on_scope_and_account ON double_entry_account_balances (scope, account);

CREATE TABLE double_entry_line_checks (
    id bigserial PRIMARY KEY,
    last_line_id bigint NOT NULL,
    errors_found boolean NOT NULL,
    log text NULL,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL
);
CREATE INDEX line_checks_created_at_last_line_id_idx ON double_entry_line_checks (created_at, last_line_id);
```

## With json metadata (jsonMetadata = true)

```sql
CREATE TABLE double_entry_lines (
    id bigserial PRIMARY KEY,
    account varchar NOT NULL,
    scope varchar NULL,
    code varchar NOT NULL,
    amount bigint NOT NULL,
    balance bigint NOT NULL,
    partner_id bigint NULL,
    partner_account varchar NOT NULL,
    partner_scope varchar NULL,
    detail_type varchar NULL,
    detail_id bigint NULL,
    metadata jsonb NULL,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL
);
CREATE INDEX lines_account_code_created_at_idx ON double_entry_lines (account, code, created_at);
CREATE INDEX lines_account_created_at_idx ON double_entry_lines (account, created_at);
CREATE INDEX lines_scope_account_created_at_idx ON double_entry_lines (scope, account, created_at);
CREATE INDEX lines_scope_account_id_idx ON double_entry_lines (scope, account, id);
```

## With the line metadata table (jsonMetadata = false)

```sql
CREATE TABLE double_entry_lines (
    id bigserial PRIMARY KEY,
    account varchar NOT NULL,
    scope varchar NULL,
    code varchar NOT NULL,
    amount bigint NOT NULL,
    balance bigint NOT NULL,
    partner_id bigint NULL,
    partner_account varchar NOT NULL,
    partner_scope varchar NULL,
    detail_type varchar NULL,
    detail_id bigint NULL,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL
);
CREATE INDEX lines_account_code_created_at_idx ON double_entry_lines (account, code, created_at);
CREATE INDEX lines_account_created_at_idx ON double_entry_lines (account, created_at);
CREATE INDEX lines_scope_account_created_at_idx ON double_entry_lines (scope, account, created_at);
CREATE INDEX lines_scope_account_id_idx ON double_entry_lines (scope, account, id);

CREATE TABLE double_entry_line_metadata (
    id bigserial PRIMARY KEY,
    line_id bigint NOT NULL,
    key varchar NOT NULL,
    value varchar NOT NULL,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL
);
CREATE INDEX lines_meta_line_id_key_value_idx ON double_entry_line_metadata (line_id, key, value);
```

## Notes

- The gem's migration run on PostgreSQL has the same columns, nullability, lengths, precisions, defaults and indexes:
  - its `t.string` is `character varying` without a length, as Active Record's PostgreSQL adapter makes it (`varchar(255)` is MySQL's);
  - its ids are `bigserial`, the only columns with a default;
  - its `t.references` (`partner`, `detail`, `last_line`, `line`) are `bigint`, without an index or a foreign key;
  - its `t.timestamps` are `timestamp(6)` without time zone, in UTC (a `timestamp` without a precision has 6 too).
- With json metadata the migration makes `metadata` a `jsonb` on PostgreSQL (migration.rb:23-29); the port does the same.
- `DoubleEntrySchema.create` skips a table that already exists (`CREATE TABLE IF NOT EXISTS`); the DDL above does not.
