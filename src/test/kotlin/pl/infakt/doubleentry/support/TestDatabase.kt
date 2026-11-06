package pl.infakt.doubleentry.support

import java.util.concurrent.atomic.AtomicInteger
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import pl.infakt.doubleentry.schema.AccountBalancesTable
import pl.infakt.doubleentry.schema.DoubleEntrySchema
import pl.infakt.doubleentry.schema.LineChecksTable
import pl.infakt.doubleentry.schema.LineMetadataTable
import pl.infakt.doubleentry.schema.LinesTable

/**
 * The tests' PostgreSQL: one container per test JVM (CLAUDE.md), started on first use and shared; Testcontainers
 * removes it when the JVM ends. Each database on it is connected with `Database.connect(dataSource)`.
 *
 * The parity harness's schema executor reaches [empty] by reflection at run time (build.gradle.kts, task `parity`).
 */
object TestDatabase {
    private const val IMAGE = "postgres:17-alpine"

    private val container: PostgreSQLContainer<Nothing> by lazy {
        PostgreSQLContainer<Nothing>(DockerImageName.parse(IMAGE)).apply { start() }
    }

    private val databases = AtomicInteger()

    private val ledgers = mutableMapOf<Boolean, Database>()

    /**
     * The shared database with the generator's tables for [jsonMetadata] (DoubleEntrySchema), created on first use,
     * with every table emptied and its ids restarted: call it before each test.
     */
    @Synchronized
    fun ledger(jsonMetadata: Boolean): Database {
        val database = ledgers.getOrPut(jsonMetadata) { empty().also { DoubleEntrySchema.create(it, jsonMetadata) } }
        val tables = DoubleEntrySchema.tables(jsonMetadata).joinToString { it.tableName }
        transaction(database) { exec("TRUNCATE $tables RESTART IDENTITY") }
        return database
    }

    /** The tables of the spec shape: the lines with the `metadata` column, and `double_entry_line_metadata`. */
    private val specTables: List<Table> =
        listOf(AccountBalancesTable, LinesTable.withJsonMetadata, LineChecksTable, LineMetadataTable)

    private var spec: Database? = null

    /**
     * The shared database with the gem's spec shape (spec/support/schema.rb): the generator's tables with json
     * metadata, plus `double_entry_line_metadata`, so the lines have their `metadata` column and the metadata table is
     * there too. Created on first use, with every table emptied and its ids restarted: call it before each test.
     */
    @Synchronized
    fun specLedger(): Database {
        val database =
            spec ?: empty().also { created ->
                DoubleEntrySchema.create(created, jsonMetadata = true)
                transaction(created) { SchemaUtils.create(LineMetadataTable) }
                spec = created
            }
        val tables = specTables.joinToString { it.tableName }
        transaction(database) { exec("TRUNCATE $tables RESTART IDENTITY") }
        return database
    }

    /** A new database of its own on the shared container, without any table. */
    fun empty(): Database {
        val name = "double_entry_${databases.incrementAndGet()}"
        dataSource(container.databaseName).connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        return Database.connect(dataSource(name))
    }

    private fun dataSource(databaseName: String): PGSimpleDataSource =
        PGSimpleDataSource().apply {
            val port = container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
            setUrl("jdbc:postgresql://${container.host}:$port/$databaseName")
            user = container.username
            password = container.password
        }
}
