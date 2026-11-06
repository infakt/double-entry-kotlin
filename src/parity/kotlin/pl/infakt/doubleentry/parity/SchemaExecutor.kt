package pl.infakt.doubleentry.parity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.jetbrains.exposed.v1.jdbc.Database
import pl.infakt.doubleentry.schema.DoubleEntrySchema

/**
 * The schema area: `describe_schema` on a new, empty PostgreSQL database per scenario with the tables
 * [DoubleEntrySchema] creates for `input.schema` (oracle/README.md, "Schema shapes" and "describe_schema").
 */
object SchemaExecutor : AreaExecutor {
    /** The gem's four tables, the only ones `describe_schema` and the table effects name. */
    val tableNames =
        listOf(
            "double_entry_account_balances",
            "double_entry_line_checks",
            "double_entry_line_metadata",
            "double_entry_lines",
        )

    /**
     * The oracle's `type` of a column of PostgreSQL's `data_type` [dataType], as the oracle normalizes Active Record's
     * types (oracle/README.md, "describe_schema"). A type it has no name for is shown as PostgreSQL's, so it reports
     * as a mismatch.
     */
    fun type(dataType: String): String = types[dataType] ?: dataType

    private val types =
        mapOf(
            "character varying" to "string",
            "bigint" to "integer",
            "integer" to "integer",
            "boolean" to "boolean",
            "text" to "text",
            "json" to "json",
            "jsonb" to "json",
            "timestamp without time zone" to "datetime",
            "timestamp with time zone" to "datetime",
        )

    /**
     * Starts a scenario on a new database with the generator shape for `input.schema`'s `json_metadata`; without
     * `input.schema`, json_metadata false. The `spec` shape is characterize-ledger's and no schema scenario uses it.
     */
    override fun start(input: JsonObject): ScenarioRun {
        val schema = input["schema"] as? JsonObject
        val shape = (schema?.get("shape") as? JsonPrimitive)?.content ?: "generator"
        require(shape == "generator") { "input.schema: the port creates the generator shape only, got $shape" }
        val jsonMetadata = (schema?.get("json_metadata") as? JsonPrimitive)?.booleanOrNull ?: false
        val database = TestDatabases.empty()
        DoubleEntrySchema.create(database, jsonMetadata)
        return Run(database)
    }

    private class Run(
        private val database: Database,
    ) : ScenarioRun {
        override fun run(step: JsonObject): JsonElement =
            when (step.string("op")) {
                "describe_schema" -> describe()
                else -> unknownOp(step)
            }

        /**
         * The rows of the four tables. No op of this area writes one, so each is empty; the row dump comes with the
         * ledger's ops (oracle/README.md, "Table effects").
         */
        override fun tables(): Map<String, JsonElement> = tableNames.associateWith { JsonArray(emptyList()) }

        private fun describe(): JsonElement =
            JsonObject(
                SchemaCatalog
                    .read(database)
                    .filterKeys { it in tableNames }
                    .mapValues { (_, table) -> json(table) },
            )

        private fun json(table: CatalogTable): JsonObject =
            JsonObject(
                mapOf(
                    "columns" to JsonArray(table.columns.map { json(it) }),
                    "indexes" to JsonArray(table.indexes.map { json(it) }),
                ),
            )

        private fun json(column: CatalogColumn): JsonObject =
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive(column.name),
                    "null" to JsonPrimitive(column.nullable),
                    "type" to JsonPrimitive(type(column.dataType)),
                ),
            )

        private fun json(index: CatalogIndex): JsonObject =
            JsonObject(
                mapOf(
                    "columns" to JsonArray(index.columns.map { JsonPrimitive(it) }),
                    "name" to JsonPrimitive(index.name),
                    "unique" to JsonPrimitive(index.unique),
                ),
            )
    }
}

/**
 * The databases of the tests' `pl.infakt.doubleentry.support.TestDatabase`, one PostgreSQL container per JVM. The
 * harness reaches it by reflection: the tests compile against the harness, so the harness cannot compile against
 * them; the `parity` task puts the test classes on its classpath at run time (build.gradle.kts).
 */
internal object TestDatabases {
    private const val TEST_DATABASE = "pl.infakt.doubleentry.support.TestDatabase"

    /** A new database of its own, without any table. */
    fun empty(): Database {
        val type = Class.forName(TEST_DATABASE)
        return type.getMethod("empty").invoke(type.getField("INSTANCE").get(null)) as Database
    }
}
