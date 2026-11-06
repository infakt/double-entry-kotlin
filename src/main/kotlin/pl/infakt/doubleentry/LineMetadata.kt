package pl.infakt.doubleentry

import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import pl.infakt.doubleentry.schema.LineMetadataTable

/**
 * A row of `double_entry_line_metadata`: the gem's `DoubleEntry::LineMetadata` (line_metadata.rb), one value of one key
 * of a transfer's metadata on one of its lines, [lineId]. The gem reads [key] back as a symbol (its `SymbolWrapper`);
 * the port keeps it the string it is stored as.
 */
data class LineMetadata(
    val id: Long,
    val lineId: Long,
    val key: String,
    val value: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * The line metadata rows of [database], in `double_entry_line_metadata`, the table the gem uses without
 * `json_metadata`. Each call runs in a transaction of [database] with one attempt, or in the one already open, which it
 * joins. The timestamps are the configuration's clock.
 */
internal class LineMetadataRows(
    private val database: Database,
    private val configuration: DoubleEntryConfiguration,
) {
    /**
     * Writes [metadata] for [credit] and [debit]: `Transfer#create_line_metadata` (transfer.rb:123-130). For each key,
     * in the map's order, and each of its values, a row of the credit, then one of the debit. A value that is an
     * [Iterable] or an [Array] gives one value per element, as Ruby's `Array(value)`; any other value is the one
     * value. A value is stored as its `toString()`.
     *
     * @throws IllegalArgumentException when an element of a value is `null`, where the gem's insert fails on the
     *   column's NOT NULL constraint; either way the transaction of the transfer rolls back.
     */
    fun insert(
        credit: Line,
        debit: Line,
        metadata: Map<String, Any>,
    ) {
        transaction(database) {
            maxAttempts = 1
            val now = configuration.clock.now()
            val table = LineMetadataTable
            metadata.forEach { (key, value) ->
                values(key, value).forEach { each ->
                    listOf(credit, debit).forEach { line ->
                        table.insert { row ->
                            row[table.lineId] = line.id
                            row[table.key] = key
                            row[table.value] = each
                            row[table.createdAt] = now
                            row[table.updatedAt] = now
                        }
                    }
                }
            }
        }
    }

    /**
     * The rows of the lines [lineIds], by id: the gem's `LineMetadata.where(line_id: ...)`. Read whatever the
     * configuration's `jsonMetadata` says; on a database without the table the driver's error goes through.
     */
    fun forLines(lineIds: Collection<Long>): List<LineMetadata> {
        if (lineIds.isEmpty()) return emptyList()
        return transaction(database) {
            maxAttempts = 1
            val table = LineMetadataTable
            table
                .selectAll()
                .where { table.lineId inList lineIds }
                .orderBy(table.id)
                .map { row ->
                    LineMetadata(
                        id = row[table.id].value,
                        lineId = row[table.lineId],
                        key = row[table.key],
                        value = row[table.value],
                        createdAt = row[table.createdAt],
                        updatedAt = row[table.updatedAt],
                    )
                }
        }
    }

    /** The values of [value], as Ruby's `Array(value)` makes them (transfer.rb:125), each as its string. */
    private fun values(
        key: String,
        value: Any,
    ): List<String> {
        val elements =
            when (value) {
                is Iterable<*> -> value.toList()
                is Array<*> -> value.toList()
                else -> listOf(value)
            }
        return elements.map { each -> requireNotNull(each) { "a value of the metadata key $key is null" }.toString() }
    }
}
