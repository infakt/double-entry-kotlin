package pl.infakt.doubleentry

import io.kotest.matchers.shouldBe
import java.util.Currency
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.schema.LineMetadataTable
import pl.infakt.doubleentry.schema.LinesTable
import pl.infakt.doubleentry.support.SpecAccounts
import pl.infakt.doubleentry.support.TestDatabase
import pl.infakt.doubleentry.support.TestUser

/**
 * transfer_spec.rb:50-67, 86-106 and 154-169: `Transfer.transfer` with `config.json_metadata = true`, on the spec
 * shape (spec/support/schema.rb), whose lines have the `metadata` column and which has `double_entry_line_metadata`
 * too, through `DoubleEntry.transfer`. `new_lines` are the transfer's credit and debit read back, and
 * `LineMetadata.count` the rows of the table.
 */
class TransferJsonMetadataTest {
    private val database: Database = TestDatabase.specLedger()

    private val usd: Currency = Currency.getInstance("USD")

    private val doubleEntry = facade(database)

    private val amount = Money(10_00, usd)

    private val user = TestUser(1)

    private val test = doubleEntry.account("test", scope = user)

    private val savings = doubleEntry.account("savings", scope = user)

    /** transfer_spec.rb:84. */
    private val pairs: Map<String, Any> = mapOf("country" to "AU", "tax" to "GST")

    /** transfer_spec.rb:152. */
    private val arrayValues: Map<String, Any> = mapOf("tax" to listOf("GST", "VAT"))

    @Test
    fun `without metadata, json_metadata true, does not create metadata lines`() {
        val before = metadataCount()

        transfer(metadata = null)

        metadataCount() shouldBe before
    }

    @Test
    fun `without metadata, json_metadata true, does not attach metadata to the lines`() {
        val (credit, debit) = transfer(metadata = null)

        newLines(credit, debit).map { it.metadata } shouldBe listOf(null, null)
        stored(credit, debit) shouldBe listOf(null, null)
    }

    @Test
    fun `with metadata, json_metadata true, does not create metadata lines`() {
        val before = metadataCount()

        transfer(pairs)

        metadataCount() shouldBe before
    }

    @Test
    fun `with metadata, json_metadata true, stores the first key and value pair`() {
        val (credit, debit) = transfer(pairs)

        newLines(credit, debit).count { it.metadata?.get("country") == "AU" } shouldBe 2
    }

    @Test
    fun `with metadata, json_metadata true, stores another key and value pair`() {
        val (credit, debit) = transfer(pairs)

        newLines(credit, debit).count { it.metadata?.get("tax") == "GST" } shouldBe 2
    }

    @Test
    fun `with an array for one key, json_metadata true, does not create metadata lines`() {
        val before = metadataCount()

        transfer(arrayValues)

        metadataCount() shouldBe before
    }

    @Test
    fun `with an array for one key, json_metadata true, stores both values to the same key`() {
        val (credit, debit) = transfer(arrayValues)

        newLines(credit, debit).count { it.metadata?.get("tax") == listOf("GST", "VAT") } shouldBe 2
    }

    @Test
    fun `the metadata is the json column of both lines, and the lines returned carry it as they read it back`() {
        val (credit, debit) = transfer(arrayValues)

        stored(credit, debit) shouldBe List(2) { json("""{"tax": ["GST", "VAT"]}""") }
        credit.metadata shouldBe arrayValues
        debit.metadata shouldBe arrayValues
        newLines(credit, debit).map { it.metadata } shouldBe listOf(credit.metadata, debit.metadata)
    }

    @Test
    fun `on the generator's tables with json_metadata, both lines read their metadata back`() {
        val ledger = facade(TestDatabase.ledger(jsonMetadata = true))
        val from = ledger.account("test", scope = user)
        val to = ledger.account("savings", scope = user)

        val (credit, debit) = ledger.transfer(amount, from = from, to = to, code = "bonus", metadata = pairs)

        checkNotNull(ledger.lines.find(credit.id)).metadata shouldBe pairs
        checkNotNull(ledger.lines.find(debit.id)).metadata shouldBe pairs
    }

    @Test
    fun `a value that is not a string is stored as JSON and read back as plain values`() {
        val metadata: Map<String, Any> =
            mapOf(
                "count" to 3,
                "rate" to 0.5,
                "paid" to true,
                "nested" to mapOf("codes" to arrayOf<Any>(7, "x")),
                "nan" to Double.NaN,
                "currency" to usd,
            )

        val (credit, debit) = transfer(metadata)

        val plain: Map<String, Any?> =
            mapOf(
                "count" to 3L,
                "rate" to 0.5,
                "paid" to true,
                "nested" to mapOf("codes" to listOf(7L, "x")),
                "nan" to null,
                "currency" to "USD",
            )
        newLines(credit, debit).map { it.metadata } shouldBe listOf(plain, plain)
        credit.metadata shouldBe plain
        stored(credit, debit).first() shouldBe
            json(
                """{"count": 3, "rate": 0.5, "paid": true, "nested": {"codes": [7, "x"]},""" +
                    """ "nan": null, "currency": "USD"}""",
            )
    }

    private fun facade(database: Database): DoubleEntry = SpecAccounts.doubleEntry(database) { jsonMetadata = true }

    private fun transfer(metadata: Map<String, Any>?): Pair<Line, Line> =
        doubleEntry.transfer(amount, from = test, to = savings, code = "bonus", metadata = metadata)

    /** `LineMetadata.count`. */
    private fun metadataCount(): Long = transaction(database) { LineMetadataTable.selectAll().count() }

    /** `new_lines`: [credit] and [debit] read back. */
    private fun newLines(
        credit: Line,
        debit: Line,
    ): List<Line> = listOf(credit, debit).map { checkNotNull(doubleEntry.lines.find(it.id)) }

    /** The stored `metadata` column of [credit] and of [debit]. */
    private fun stored(
        credit: Line,
        debit: Line,
    ): List<JsonObject?> {
        val lines = LinesTable.withJsonMetadata
        val column = checkNotNull(lines.metadata)
        return transaction(database) {
            listOf(credit, debit).map { line ->
                lines
                    .selectAll()
                    .where { lines.id eq line.id }
                    .single()[column]
            }
        }
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
