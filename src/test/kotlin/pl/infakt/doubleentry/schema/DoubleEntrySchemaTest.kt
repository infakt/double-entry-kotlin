package pl.infakt.doubleentry.schema

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.parity.CatalogTable
import pl.infakt.doubleentry.parity.SchemaCatalog
import pl.infakt.doubleentry.support.TestDatabase

class DoubleEntrySchemaTest {
    @Test
    fun `Line has a table name prefixed with double_entry_`() {
        LinesTable.withJsonMetadata.tableName shouldBe "double_entry_lines"
        LinesTable.withMetadataTable.tableName shouldBe "double_entry_lines"
    }

    @Test
    fun `Line persistence has a table name prefixed with double_entry_`() {
        LinesTable.of(jsonMetadata = false).tableName shouldBe "double_entry_lines"
    }

    @Test
    fun `AccountBalance table_name should eq double_entry_account_balances`() {
        AccountBalancesTable.tableName shouldBe "double_entry_account_balances"
    }

    @Test
    fun `LineCheck has a table name prefixed with double_entry_`() {
        LineChecksTable.tableName shouldBe "double_entry_line_checks"
    }

    @Test
    fun `with json metadata, create makes the balances, the lines with jsonb metadata and the line checks`() {
        val tables = created(jsonMetadata = true)

        tables.keys.toList() shouldContainExactly
            listOf("double_entry_account_balances", "double_entry_line_checks", "double_entry_lines")
        columns(tables.getValue("double_entry_lines")) shouldContainExactly lines(metadata = "metadata jsonb null")
    }

    @Test
    fun `without json metadata, create adds the line metadata table and the lines have no metadata column`() {
        val tables = created(jsonMetadata = false)

        tables.keys.toList() shouldContainExactly
            listOf(
                "double_entry_account_balances",
                "double_entry_line_checks",
                "double_entry_line_metadata",
                "double_entry_lines",
            )
        columns(tables.getValue("double_entry_lines")) shouldContainExactly lines(metadata = null)
    }

    @Test
    fun `the account balances have the migration's columns and indexes`() {
        val table = created(jsonMetadata = true).getValue("double_entry_account_balances")

        columns(table) shouldContainExactly
            listOf(
                "id bigint not null",
                "account character varying not null",
                "scope character varying null",
                "balance bigint not null",
                "created_at timestamp without time zone not null",
                "updated_at timestamp without time zone not null",
            )
        indexes(table) shouldContainExactly
            listOf(
                "index_account_balances_on_account (account)",
                "unique index_account_balances_on_scope_and_account (scope, account)",
            )
    }

    @Test
    fun `the lines have the migration's indexes`() {
        val table = created(jsonMetadata = true).getValue("double_entry_lines")

        indexes(table) shouldContainExactly
            listOf(
                "lines_account_code_created_at_idx (account, code, created_at)",
                "lines_account_created_at_idx (account, created_at)",
                "lines_scope_account_created_at_idx (scope, account, created_at)",
                "lines_scope_account_id_idx (scope, account, id)",
            )
    }

    @Test
    fun `the line checks have the migration's columns and indexes`() {
        val table = created(jsonMetadata = true).getValue("double_entry_line_checks")

        columns(table) shouldContainExactly
            listOf(
                "id bigint not null",
                "last_line_id bigint not null",
                "errors_found boolean not null",
                "log text null",
                "created_at timestamp without time zone not null",
                "updated_at timestamp without time zone not null",
            )
        indexes(table) shouldContainExactly listOf("line_checks_created_at_last_line_id_idx (created_at, last_line_id)")
    }

    @Test
    fun `the line metadata have the migration's columns and indexes`() {
        val table = created(jsonMetadata = false).getValue("double_entry_line_metadata")

        columns(table) shouldContainExactly
            listOf(
                "id bigint not null",
                "line_id bigint not null",
                "key character varying not null",
                "value character varying not null",
                "created_at timestamp without time zone not null",
                "updated_at timestamp without time zone not null",
            )
        indexes(table) shouldContainExactly listOf("lines_meta_line_id_key_value_idx (line_id, key, value)")
    }

    @Test
    fun `with json metadata, the columns have the migration's lengths, precisions and defaults`() {
        columnDetails(createdDatabase(jsonMetadata = true)) shouldContainExactly migrationDetails(jsonMetadata = true)
    }

    @Test
    fun `without json metadata, the columns have the migration's lengths, precisions and defaults`() {
        columnDetails(createdDatabase(jsonMetadata = false)) shouldContainExactly migrationDetails(jsonMetadata = false)
    }

    @Test
    fun `the tables have no foreign key, as the migration's references have none`() {
        listOf(true, false).forEach { jsonMetadata ->
            val foreignKeys =
                transaction(createdDatabase(jsonMetadata)) {
                    exec(FOREIGN_KEYS) { result ->
                        buildList {
                            while (result.next()) add(result.getString(1))
                        }
                    }
                }

            foreignKeys shouldBe emptyList()
        }
    }

    @Test
    fun `drop removes every table of either shape`() {
        listOf(true, false).forEach { jsonMetadata ->
            val database = TestDatabase.empty()
            DoubleEntrySchema.create(database, jsonMetadata)

            DoubleEntrySchema.drop(database)

            SchemaCatalog.read(database).shouldBeEmpty()
        }
    }

    @Test
    fun `drop on a database without the tables does nothing`() {
        val database = TestDatabase.empty()

        DoubleEntrySchema.drop(database)

        SchemaCatalog.read(database).shouldBeEmpty()
    }

    /** The tables of a new database after [DoubleEntrySchema.create] for [jsonMetadata]. */
    private fun created(jsonMetadata: Boolean): Map<String, CatalogTable> =
        SchemaCatalog.read(createdDatabase(jsonMetadata))

    /** A new database after [DoubleEntrySchema.create] for [jsonMetadata]. */
    private fun createdDatabase(jsonMetadata: Boolean): Database =
        TestDatabase.empty().also { DoubleEntrySchema.create(it, jsonMetadata) }

    /**
     * Each column of [database] as `<table>.<column> length=<n> precision=<p> default=<d>`, from
     * `information_schema.columns`, by table name and then in the table's order.
     */
    private fun columnDetails(database: Database): List<String> =
        transaction(database) {
            exec(COLUMN_DETAILS) { result ->
                buildList {
                    while (result.next()) {
                        add(
                            "${result.getString(1)}.${result.getString(2)} length=${result.getString(3)} " +
                                "precision=${result.getString(4)} default=${result.getString(5)}",
                        )
                    }
                }
            }.orEmpty()
        }

    /**
     * What the migration gives on PostgreSQL for [jsonMetadata], in [columnDetails]' form: no length on a `t.string`
     * (Active Record's `character varying` without a `limit`), a precision of 6 on the `t.timestamps` (`timestamp(6)`),
     * and no default but the `bigserial` id's sequence.
     */
    private fun migrationDetails(jsonMetadata: Boolean): List<String> =
        listOfNotNull(
            "double_entry_account_balances" to listOf("id", "account", "scope", "balance") + TIMESTAMPS,
            "double_entry_line_checks" to listOf("id", "last_line_id", "errors_found", "log") + TIMESTAMPS,
            ("double_entry_line_metadata" to listOf("id", "line_id", "key", "value") + TIMESTAMPS)
                .takeUnless { jsonMetadata },
            "double_entry_lines" to
                lines(metadata = "metadata".takeIf { jsonMetadata }).map { it.substringBefore(' ') },
        ).flatMap { (table, columns) ->
            columns.map { column ->
                val precision = if (column in TIMESTAMPS) "6" else null
                val default = if (column == "id") "nextval('${table}_id_seq'::regclass)" else null
                "$table.$column length=null precision=$precision default=$default"
            }
        }

    /** The lines' columns (migration.rb:13-31), with the [metadata] column before the timestamps when there is one. */
    private fun lines(metadata: String?): List<String> =
        listOfNotNull(
            "id bigint not null",
            "account character varying not null",
            "scope character varying null",
            "code character varying not null",
            "amount bigint not null",
            "balance bigint not null",
            "partner_id bigint null",
            "partner_account character varying not null",
            "partner_scope character varying null",
            "detail_type character varying null",
            "detail_id bigint null",
            metadata,
            "created_at timestamp without time zone not null",
            "updated_at timestamp without time zone not null",
        )

    /** Each column as `<name> <PostgreSQL type> null|not null`. */
    private fun columns(table: CatalogTable): List<String> =
        table.columns.map { "${it.name} ${it.dataType} ${if (it.nullable) "null" else "not null"}" }

    /** Each index as `[unique ]<name> (<columns>)`. */
    private fun indexes(table: CatalogTable): List<String> =
        table.indexes.map { "${if (it.unique) "unique " else ""}${it.name} (${it.columns.joinToString()})" }

    private companion object {
        /** The columns of `t.timestamps`. */
        val TIMESTAMPS = listOf("created_at", "updated_at")

        const val COLUMN_DETAILS =
            "SELECT table_name, column_name, character_maximum_length, datetime_precision, column_default " +
                "FROM information_schema.columns WHERE table_schema = current_schema() " +
                "ORDER BY table_name, ordinal_position"

        const val FOREIGN_KEYS =
            "SELECT constraint_name FROM information_schema.table_constraints " +
                "WHERE table_schema = current_schema() AND constraint_type = 'FOREIGN KEY'"
    }
}
