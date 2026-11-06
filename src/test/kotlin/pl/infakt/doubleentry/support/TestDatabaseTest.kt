package pl.infakt.doubleentry.support

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.parity.SchemaCatalog

class TestDatabaseTest {
    @Test
    fun `a ledger database has the generator's tables for its json metadata`() {
        SchemaCatalog.read(TestDatabase.ledger(jsonMetadata = true)).keys.toList() shouldContainExactly
            listOf("double_entry_account_balances", "double_entry_line_checks", "double_entry_lines")
        SchemaCatalog.read(TestDatabase.ledger(jsonMetadata = false)).keys.toList() shouldContainExactly
            listOf(
                "double_entry_account_balances",
                "double_entry_line_checks",
                "double_entry_line_metadata",
                "double_entry_lines",
            )
    }

    @Test
    fun `the spec ledger has the lines' metadata column and the line metadata table both`() {
        val tables = SchemaCatalog.read(TestDatabase.specLedger())

        tables.keys.toList() shouldContainExactly
            listOf(
                "double_entry_account_balances",
                "double_entry_line_checks",
                "double_entry_line_metadata",
                "double_entry_lines",
            )
        tables.getValue("double_entry_lines").columns.map { it.name } shouldContain "metadata"
    }

    @Test
    fun `the spec ledger is emptied and its ids restart each time it is asked for`() {
        val database = TestDatabase.specLedger()
        insertBalance(database) shouldBe 1L

        TestDatabase.specLedger() shouldBe database

        count(database) shouldBe 0L
        insertBalance(database) shouldBe 1L
    }

    @Test
    fun `a ledger database is emptied and its ids restart each time it is asked for`() {
        listOf(true, false).forEach { jsonMetadata ->
            val database = TestDatabase.ledger(jsonMetadata)
            insertBalance(database) shouldBe 1L
            insertBalance(database) shouldBe 2L

            TestDatabase.ledger(jsonMetadata) shouldBe database

            count(database) shouldBe 0L
            insertBalance(database) shouldBe 1L
        }
    }

    @Test
    fun `each empty database is a new one without tables`() {
        val first = TestDatabase.empty()
        transaction(first) { exec("CREATE TABLE made_up (id bigint)") }

        SchemaCatalog.read(TestDatabase.empty()).keys shouldBe emptySet()
    }

    /** Inserts an account balance with made-up values and returns its id. */
    private fun insertBalance(database: Database): Long =
        query(
            database,
            "INSERT INTO double_entry_account_balances (account, balance, created_at, updated_at) " +
                "VALUES ('savings', 0, now(), now()) RETURNING id",
        )

    private fun count(database: Database): Long = query(database, "SELECT count(*) FROM double_entry_account_balances")

    private fun query(
        database: Database,
        sql: String,
    ): Long =
        transaction(database) {
            exec(sql, explicitStatementType = StatementType.SELECT) { result ->
                result.next()
                result.getLong(1)
            }
        } ?: error("no result for $sql")
}
