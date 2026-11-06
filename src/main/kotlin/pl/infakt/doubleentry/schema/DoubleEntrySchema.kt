package pl.infakt.doubleentry.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * The library's tables: the install generator's migration (lib/generators/double_entry/install/templates/migration.rb)
 * on the caller's database. The gem leaves the migration to the application; the port creates the same tables, their
 * columns, nullability and indexes, for an application or a test to call.
 */
object DoubleEntrySchema {
    /**
     * Creates the tables in [database], as the migration's `up` does for [jsonMetadata] (migration.rb:2-56): the
     * account balances, the lines and the line checks, and then, with [jsonMetadata], a `jsonb` metadata column on the
     * lines; without, the `double_entry_line_metadata` table.
     *
     * A table that already exists is skipped (`CREATE TABLE IF NOT EXISTS`), where the migration's `up` would raise;
     * its shape is not checked. Calling this with the other [jsonMetadata] on a database already created leaves the
     * existing tables as they are and only adds the missing ones.
     */
    fun create(
        database: Database,
        jsonMetadata: Boolean,
    ) {
        transaction(database) { SchemaUtils.create(*tables(jsonMetadata).toTypedArray()) }
    }

    /**
     * Drops the tables from [database], as the migration's `down` does (migration.rb:58-63): the line metadata if it
     * is there, then the line checks, the lines and the account balances. A table that is not there is skipped.
     */
    fun drop(database: Database) {
        transaction(database) {
            SchemaUtils.drop(LineMetadataTable, LineChecksTable, LinesTable.withJsonMetadata, AccountBalancesTable)
        }
    }

    /** The tables of the shape [jsonMetadata] selects, in the migration's order. */
    internal fun tables(jsonMetadata: Boolean): List<Table> =
        listOfNotNull(
            AccountBalancesTable,
            LinesTable.of(jsonMetadata),
            LineChecksTable,
            LineMetadataTable.takeUnless { jsonMetadata },
        )
}
