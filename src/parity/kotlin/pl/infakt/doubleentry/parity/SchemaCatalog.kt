package pl.infakt.doubleentry.parity

import java.sql.ResultSet
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** A column as PostgreSQL's `information_schema.columns` lists it; [dataType] is its `data_type`, as is. */
data class CatalogColumn(
    val name: String,
    val nullable: Boolean,
    val dataType: String,
)

/** An index other than a primary key, with its [columns] in index order. */
data class CatalogIndex(
    val name: String,
    val unique: Boolean,
    val columns: List<String>,
)

/** A table's [columns] in table order and its [indexes] by name. */
data class CatalogTable(
    val columns: List<CatalogColumn>,
    val indexes: List<CatalogIndex>,
)

/**
 * Reads the tables of a PostgreSQL database's current schema from its catalog: the columns from
 * `information_schema.columns`, the indexes from `pg_index`, the catalog `pg_indexes` is a view of, which also says
 * which index is the primary key and gives each index's columns in order. The schema executor and the schema tests
 * read the port's tables through it.
 */
object SchemaCatalog {
    /** Every table of [database]'s current schema, by name. */
    fun read(database: Database): Map<String, CatalogTable> =
        transaction(database) {
            query(TABLES) { it.getString("table_name") }
                .sorted()
                .associateWith { CatalogTable(columns(it), indexes(it)) }
        }

    private fun JdbcTransaction.columns(table: String): List<CatalogColumn> =
        query(COLUMNS, table) {
            CatalogColumn(it.getString("column_name"), it.getString("is_nullable") == "YES", it.getString("data_type"))
        }

    private fun JdbcTransaction.indexes(table: String): List<CatalogIndex> =
        query(INDEXES, table) { IndexColumn(it) }
            .groupBy { it.index to it.unique }
            .map { (index, columns) -> CatalogIndex(index.first, index.second, columns.map { it.column }) }
            .sortedBy { it.name }

    /** One row of [INDEXES]: one column of one index. */
    private class IndexColumn(
        row: ResultSet,
    ) {
        val index: String = row.getString("index_name")
        val unique: Boolean = row.getBoolean("is_unique")
        val column: String = row.getString("column_name")
    }

    private fun <T> JdbcTransaction.query(
        sql: String,
        vararg arguments: String,
        row: (ResultSet) -> T,
    ): List<T> =
        exec(sql, arguments.map { TextColumnType() to it }) { result ->
            buildList { while (result.next()) add(row(result)) }
        } ?: emptyList()

    private const val TABLES =
        "SELECT table_name FROM information_schema.tables " +
            "WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'"

    private const val COLUMNS =
        "SELECT column_name, is_nullable, data_type FROM information_schema.columns " +
            "WHERE table_schema = current_schema() AND table_name::text = ? ORDER BY ordinal_position"

    // indkey holds the indexed columns' numbers in index order; WITH ORDINALITY keeps that order.
    private const val INDEXES =
        "SELECT i.relname AS index_name, x.indisunique AS is_unique, a.attname AS column_name " +
            "FROM pg_index x " +
            "JOIN pg_class t ON t.oid = x.indrelid " +
            "JOIN pg_class i ON i.oid = x.indexrelid " +
            "JOIN pg_namespace n ON n.oid = t.relnamespace " +
            "CROSS JOIN LATERAL unnest(x.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord) " +
            "JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum " +
            "WHERE n.nspname = current_schema() AND t.relname::text = ? AND NOT x.indisprimary " +
            "ORDER BY i.relname, k.ord"
}
