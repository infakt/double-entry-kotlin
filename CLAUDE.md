# CLAUDE.md

A Kotlin port of the Ruby gem `double_entry` (double-entry accounting on a SQL database). Read `docs/decisions/0001-port-double-entry-to-kotlin.md` first: it fixes the scope, the data layer and the API style.

## Commands

`./gradlew test` · `./gradlew ktlintCheck` (`./gradlew ktlintFormat` fixes style) · `./gradlew build`. Before finishing a change: `test` and `ktlintCheck` both green.

There is no detekt in this project.

## Rules

- Kotlin 2.4, JDK 25, package `pl.infakt.doubleentry`. Library only: no Spring, no web layer.
- Data layer: Exposed 1.5 over JDBC. The caller gives the `Database`; the library opens no connection.
- Tests: JUnit 5 and Kotest assertions (`io.kotest.matchers`). No mocking library; use a real database.
- The test database is PostgreSQL in a container through Testcontainers (`org.testcontainers:postgresql`, image `postgres:17-alpine`), one container per test JVM, started once and shared. Never an embedded PostgreSQL (zonky and the like).
- Never edit anything under `reference/`: it is the specification.
- Identifiers, comments and commit messages in English. Style by ktlint (`.editorconfig`).
- The library's API is idiomatic Kotlin, not a transliteration of Ruby; behaviour is the gem's.
- No personal or client data anywhere, in code, tests or fixtures; use made-up values.
- Never commit credential files (`.env*` but `.env.example`, private keys, kubeconfig, `*.tfstate`…).
