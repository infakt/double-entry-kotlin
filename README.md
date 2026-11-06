# double-entry (Kotlin)

Double-entry accounting for the JVM: accounts, transfers between them, balances, locking and consistency checks, on a SQL database through Exposed. A port of the Ruby gem [`double_entry`](https://github.com/envato/double_entry) 2.0.2 (see [NOTICE.md](NOTICE.md)).

The decisions are in [docs/decisions](docs/decisions/0001-port-double-entry-to-kotlin.md).

## Usage

The Gradle coordinate is `pl.infakt:double-entry:<version>`:

```
dependencies {
    implementation("pl.infakt:double-entry:<version>")
}
```

The library opens no connection: you connect an Exposed `Database` and hand it over. The tests run it on PostgreSQL 17. The snippet below is `ReadmeExampleTest`.

```kotlin
import java.util.Currency
import org.jetbrains.exposed.v1.jdbc.Database
import pl.infakt.doubleentry.DoubleEntry
import pl.infakt.doubleentry.Money
import pl.infakt.doubleentry.schema.DoubleEntrySchema

val database = Database.connect(dataSource)

// The tables, once; or the same DDL in a migration of your own (docs/schema.md).
DoubleEntrySchema.create(database, jsonMetadata = false)

// Configuration: the accounts and the transfers allowed between them.
val doubleEntry =
    DoubleEntry(database) {
        defaultCurrency = Currency.getInstance("EUR")
        accounts {
            define("cash")
            define("wallet", scopeIdentifier = { customerId -> customerId }, positiveOnly = true)
            define("fees", scopeIdentifier = { customerId -> customerId })
        }
        transfers {
            define(from = "cash", to = "wallet", code = "top_up")
            define(from = "wallet", to = "fees", code = "fee")
        }
    }

val cash = doubleEntry.account("cash")
val wallet = doubleEntry.account("wallet", scope = 42L)
val fees = doubleEntry.account("fees", scope = 42L)

// Transfer: two lines, the credit of cash and the debit of the wallet.
doubleEntry.transfer(Money.of(100_00, "EUR"), from = cash, to = wallet, code = "top_up")

// Balance: 100.00 EUR, the same as wallet.balance().
val balance = doubleEntry.balance(wallet)

// Lock the accounts to read a balance and transfer on it in one transaction.
doubleEntry.lockAccounts(wallet, fees) {
    if (wallet.balance() >= Money.of(5_00, "EUR")) {
        doubleEntry.transfer(Money.of(5_00, "EUR"), from = wallet, to = fees, code = "fee")
    }
}

// Validation: check the lines written since the last check and the balance records, and correct what is wrong.
val check = doubleEntry.lineCheck.perform(doubleEntry.accountFixer)
check?.errorsFound // false: every balance was right
```

`DoubleEntrySchema.create` and `DoubleEntryConfiguration.jsonMetadata` must agree: with `jsonMetadata = true` a transfer's metadata goes to a `jsonb` column of the lines, without it to the table `double_entry_line_metadata`.

More:

- [docs/schema.md](docs/schema.md): the PostgreSQL DDL of both shapes.
- [docs/parity.md](docs/parity.md): each example of the gem's specs and the test that ports it.
- [docs/deviations.md](docs/deviations.md): where the port behaves differently from the gem, and why.
- [./oracle/replay](oracle/replay): replays the scenarios recorded on the gem against the port ([oracle/README.md](oracle/README.md)); it ends with `parity: N scenarios, 0 mismatches`.

## Build

```
./gradlew test
./gradlew ktlintCheck
```

Needs JDK 25 (`JAVA_HOME`).
