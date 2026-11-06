# 0001: Port `double_entry` to a Kotlin library

Status: accepted (the person's task, 2026-11-02).

## Context

`double_entry` (Envato, MIT, version 2.0.2) is a Ruby gem for double-entry accounting on ActiveRecord: accounts, transfers between them, account balances, locking and consistency checks. Its source is pinned under `reference/double_entry/` (see `reference/SOURCE_SHA`): about 1500 lines of library code and 2000 lines of RSpec. The goal is a **complete** port as a JVM library, not a service.

## Decisions

1. **A library, not an application.** No Spring, no web layer. Kotlin 2.4, JDK 25, package `pl.infakt.doubleentry`. The organisation's toolchain and versions are those of `skeleton-spring-app` (`gradle/libs.versions.toml`).
2. **Data layer: Exposed 1.5 over JDBC**, the organisation's ORM. The library takes a `Database` from the caller and never opens connections itself. Production target is PostgreSQL. The gem's locking semantics (a row lock on the account balance, ordered locks over several accounts, a retry on deadlock) are kept, written for Exposed's transactions. Which database engine the tests run on is not decided here: the plan decides it.
3. **Money: our own small value type**, not a money library. An amount is a `Long` of minor units with a `java.util.Currency`; arithmetic between currencies is an error, as in the gem.
4. **An idiomatic Kotlin API**, not a transliteration: a configuration DSL for accounts and transfers, `DoubleEntry.transfer(...)`, `DoubleEntry.balance(...)`, a sealed hierarchy of exceptions in place of the gem's error classes. The behaviour is the gem's; the names and shapes are Kotlin's.
5. **Not ported:** the Rails install generator (`lib/generators`). It is replaced by `DoubleEntrySchema` (creates the three tables through Exposed's `SchemaUtils`) and the equivalent SQL in the docs. The gem's performance spec is ported last, as an optional benchmark.
6. **Licence:** MIT, as the source. `NOTICE.md` keeps the attribution; `reference/double_entry/LICENSE.md` stays.

## Consequences

- `reference/` is read-only for every stage: it is the specification.
