package pl.infakt.doubleentry.schema

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKeys
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.readLines
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.parity.SchemaCatalog
import pl.infakt.doubleentry.support.TestDatabase

/**
 * Runs the SQL of docs/schema.md on a new database, for each shape, and checks that it gives what
 * [DoubleEntrySchema.create] gives on another: the same tables, columns (type, nullability, length, default) and
 * indexes.
 */
class SchemaDocTest {
    private val sections: Map<String, String> = sqlSections(Path.of("docs/schema.md").readLines())

    @Test
    fun `docs schema md has the sql of both shapes and of each`() {
        sections.shouldContainKeys(BOTH, JSON_METADATA, METADATA_TABLE)
    }

    @Test
    fun `the sql of docs schema md with json metadata makes the tables DoubleEntrySchema create makes`() {
        sameSchema(jsonMetadata = true, section = JSON_METADATA)
    }

    @Test
    fun `the sql of docs schema md with the line metadata table makes the tables DoubleEntrySchema create makes`() {
        sameSchema(jsonMetadata = false, section = METADATA_TABLE)
    }

    private fun sameSchema(
        jsonMetadata: Boolean,
        section: String,
    ) {
        val fromDoc = TestDatabase.empty()
        execute(fromDoc, sections.getValue(BOTH))
        execute(fromDoc, sections.getValue(section))
        val fromCreate = TestDatabase.empty()
        DoubleEntrySchema.create(fromCreate, jsonMetadata)

        val expected = SchemaCatalog.read(fromCreate)
        expected.keys shouldBe DoubleEntrySchema.tables(jsonMetadata).map { it.tableName }.toSet()
        SchemaCatalog.read(fromDoc) shouldBe expected
        columnDetails(fromDoc) shouldContainExactly columnDetails(fromCreate)
    }

    /** Runs each statement of [sql], the statements separated by `;`, in one transaction of [database]. */
    private fun execute(
        database: Database,
        sql: String,
    ) {
        transaction(database) {
            sql
                .split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { exec(it) }
        }
    }

    /**
     * Each column of [database]'s current schema as `<table> <column> <length> <precision> <default>`, what
     * [SchemaCatalog] does not read: a `varchar`'s length, a `timestamp`'s precision and the `bigserial` sequence.
     */
    private fun columnDetails(database: Database): List<String> =
        transaction(database) {
            exec(COLUMN_DETAILS) { result ->
                buildList {
                    while (result.next()) add((1..5).joinToString(" ") { result.getString(it) ?: "null" })
                }
            } ?: emptyList()
        }

    private companion object {
        const val BOTH = "Both shapes"
        const val JSON_METADATA = "With json metadata (jsonMetadata = true)"
        const val METADATA_TABLE = "With the line metadata table (jsonMetadata = false)"

        const val COLUMN_DETAILS =
            "SELECT table_name, column_name, character_maximum_length, datetime_precision, column_default " +
                "FROM information_schema.columns WHERE table_schema = current_schema() " +
                "ORDER BY table_name, ordinal_position"

        /** The ```sql blocks of a Markdown file, by the title of the `## ` heading they are under, joined. */
        fun sqlSections(lines: List<String>): Map<String, String> {
            val sections = LinkedHashMap<String, StringBuilder>()
            var heading: String? = null
            var inSql = false
            for (line in lines) {
                when {
                    inSql && line.trim() == "```" -> inSql = false
                    inSql -> heading?.let { sections.getOrPut(it) { StringBuilder() }.appendLine(line) }
                    line.trim() == "```sql" -> inSql = true
                    line.startsWith("## ") -> heading = line.removePrefix("## ").trim()
                }
            }
            return sections.mapValues { it.value.toString() }
        }
    }
}
