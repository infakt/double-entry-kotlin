package pl.infakt.doubleentry.parity

import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import pl.infakt.doubleentry.schema.DoubleEntrySchema

/**
 * The `effects.<table>` entries of a scenario run on [database], created for [jsonMetadata]: the rows of each of the
 * gem's four tables, by id, each with every column of the table (oracle/README.md, "Table effects"). A table the shape
 * lacks is `[]`. Each cell is read through the port's column, so a timestamp reads back as the port wrote it, and is
 * written to the second in UTC.
 */
internal fun ledgerTables(
    database: Database,
    jsonMetadata: Boolean,
): Map<String, JsonElement> {
    val tables = DoubleEntrySchema.tables(jsonMetadata).associateBy { it.tableName }
    return transaction(database) {
        maxAttempts = 1
        SchemaExecutor.tableNames.associateWith { name ->
            (tables[name] as? LongIdTable)?.let { rows(it) } ?: JsonArray(emptyList())
        }
    }
}

/** The rows of [table] by id, each with every column, as [ledgerTables] writes them; call it in a transaction. */
internal fun rows(table: LongIdTable): JsonArray =
    JsonArray(
        table.selectAll().orderBy(table.id).map { row ->
            JsonObject(table.columns.associate { column -> column.name to cell(row[column]) })
        },
    )

/** A cell as the oracle writes it (oracle/README.md, "Values"). A type without an oracle form is a harness error. */
private fun cell(value: Any?): JsonElement =
    when (value) {
        null -> JsonNull
        is EntityID<*> -> cell(value.value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Instant -> JsonPrimitive(Instant.fromEpochSeconds(value.epochSeconds).toString())
        is JsonElement -> value
        else -> error("no oracle value for a cell of ${value::class}")
    }
