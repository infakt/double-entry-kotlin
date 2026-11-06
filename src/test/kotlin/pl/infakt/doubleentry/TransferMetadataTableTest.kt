package pl.infakt.doubleentry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.Currency
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.schema.LineMetadataTable
import pl.infakt.doubleentry.schema.LinesTable
import pl.infakt.doubleentry.support.SpecAccounts
import pl.infakt.doubleentry.support.TestDatabase
import pl.infakt.doubleentry.support.TestUser

/**
 * transfer_spec.rb:69-80, 108-148 and 171-198: `Transfer.transfer` with `config.json_metadata = false`, on the spec
 * shape (spec/support/schema.rb), whose lines have the `metadata` column and which has `double_entry_line_metadata`
 * too, through `DoubleEntry.transfer`. `new_lines` are the transfer's credit and debit, `new_metadata` the rows
 * `DoubleEntry.lineMetadata` reads for them, and `LineMetadata.count` the rows of the table.
 */
class TransferMetadataTableTest {
    private val database: Database = TestDatabase.specLedger()

    private val usd: Currency = Currency.getInstance("USD")

    private val frozen = Instant.parse("2024-01-01T00:00:00Z")

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
    fun `without metadata, json_metadata false, does not create metadata lines`() {
        val before = metadataCount()

        transfer(metadata = null)

        metadataCount() shouldBe before
    }

    @Test
    fun `without metadata, json_metadata false, does not attach metadata to the lines`() {
        val (credit, debit) = transfer(metadata = null)

        attachedMetadata(credit, debit) shouldBe List(4) { null }
    }

    @Test
    fun `with metadata, json_metadata false, creates metadata lines`() {
        val before = metadataCount()

        transfer(pairs)

        metadataCount() shouldBe before + 4
    }

    @Test
    fun `with metadata, json_metadata false, associates the metadata lines with the transfer lines`() {
        val (credit, debit) = transfer(pairs)

        val metadata = newMetadata(credit, debit)

        metadata.count { it.lineId == credit.id } shouldBe 2
        metadata.count { it.lineId == debit.id } shouldBe 2
    }

    @Test
    fun `with metadata, json_metadata false, stores the first key and value pair`() {
        val (credit, debit) = transfer(pairs)

        val countries = newMetadata(credit, debit).filter { it.key == "country" }

        countries.size shouldBe 2
        countries.count { it.value == "AU" } shouldBe 2
    }

    @Test
    fun `with metadata, json_metadata false, associates the first key and value pair with both lines`() {
        val (credit, debit) = transfer(pairs)

        val countries = newMetadata(credit, debit).filter { it.key == "country" }

        countries.map { it.lineId }.distinct().size shouldBe 2
    }

    @Test
    fun `with metadata, json_metadata false, stores another key and value pair`() {
        val (credit, debit) = transfer(pairs)

        val taxes = newMetadata(credit, debit).filter { it.key == "tax" }

        taxes.size shouldBe 2
        taxes.count { it.value == "GST" } shouldBe 2
    }

    @Test
    fun `with metadata, json_metadata false, does not attach metadata to the lines`() {
        val (credit, debit) = transfer(pairs)

        attachedMetadata(credit, debit) shouldBe List(4) { null }
    }

    @Test
    fun `with an array for one key, json_metadata false, creates metadata lines`() {
        val before = metadataCount()

        transfer(arrayValues)

        metadataCount() shouldBe before + 4
    }

    @Test
    fun `with an array for one key, json_metadata false, associates the metadata lines with the transfer lines`() {
        val (credit, debit) = transfer(arrayValues)

        val metadata = newMetadata(credit, debit)

        metadata.count { it.lineId == credit.id } shouldBe 2
        metadata.count { it.lineId == debit.id } shouldBe 2
    }

    @Test
    fun `with an array for one key, json_metadata false, stores both values to the same key`() {
        val (credit, debit) = transfer(arrayValues)

        val taxes = newMetadata(credit, debit).filter { it.key == "tax" }

        taxes.size shouldBe 4
        taxes.count { it.value == "GST" } shouldBe 2
        taxes.map { it.lineId }.distinct().size shouldBe 2
    }

    @Test
    fun `with an array for one key, json_metadata false, does not attach metadata to the lines`() {
        val (credit, debit) = transfer(arrayValues)

        attachedMetadata(credit, debit) shouldBe List(4) { null }
    }

    @Test
    fun `rows go key by key, value by value, the credit's before the debit's, with the clock's timestamps`() {
        val (credit, debit) = transfer(mapOf("tax" to listOf("GST", "VAT"), "country" to "AU"))

        newMetadata(credit, debit) shouldBe
            listOf(
                LineMetadata(1, credit.id, "tax", "GST", frozen, frozen),
                LineMetadata(2, debit.id, "tax", "GST", frozen, frozen),
                LineMetadata(3, credit.id, "tax", "VAT", frozen, frozen),
                LineMetadata(4, debit.id, "tax", "VAT", frozen, frozen),
                LineMetadata(5, credit.id, "country", "AU", frozen, frozen),
                LineMetadata(6, debit.id, "country", "AU", frozen, frozen),
            )
    }

    @Test
    fun `lineMetadata gives only the rows of the lines asked for, and none for no line`() {
        val (credit, _) = transfer(pairs)

        doubleEntry.lineMetadata(listOf(credit.id)).map { it.lineId }.distinct() shouldBe listOf(credit.id)
        doubleEntry.lineMetadata(emptyList()).shouldBeEmpty()
    }

    @Test
    fun `a value that is not a string is stored as its string, an array as its elements`() {
        val (credit, debit) = transfer(mapOf("count" to 3, "codes" to arrayOf<Any>(7, "x")))

        newMetadata(credit, debit).map { it.key to it.value } shouldBe
            listOf("count" to "3", "count" to "3", "codes" to "7", "codes" to "7", "codes" to "x", "codes" to "x")
    }

    @Test
    fun `a null element of a value rolls the transfer back and writes nothing`() {
        shouldThrow<IllegalArgumentException> { transfer(mapOf("tax" to listOf("GST", null))) }

        metadataCount() shouldBe 0L
        transaction(database) { LinesTable.withJsonMetadata.selectAll().count() } shouldBe 0L
    }

    @Test
    fun `with json_metadata true, a line read back has its metadata column as plain values`() {
        val (credit, debit) = transfer(metadata = null)
        val lines = LinesTable.withJsonMetadata
        val column = checkNotNull(lines.metadata)
        val stored =
            Json
                .parseToJsonElement(
                    """{"country": "AU", "tax": ["GST", "VAT"], "count": 3, "rate": 0.5, "paid": true, "note": null}""",
                ).jsonObject
        transaction(database) { lines.update(where = { lines.id eq credit.id }) { it[column] = stored } }
        val json = SpecAccounts.doubleEntry(database) { jsonMetadata = true }

        checkNotNull(json.lines.find(credit.id)).metadata shouldBe
            mapOf(
                "country" to "AU",
                "tax" to listOf("GST", "VAT"),
                "count" to 3L,
                "rate" to 0.5,
                "paid" to true,
                "note" to null,
            )
        checkNotNull(json.lines.find(debit.id)).metadata.shouldBeNull()
        checkNotNull(doubleEntry.lines.find(credit.id)).metadata.shouldBeNull()
    }

    @Test
    fun `on the generator's tables without json_metadata, a line's metadata is null (X-04)`() {
        val ledger = facade(TestDatabase.ledger(jsonMetadata = false))
        val from = ledger.account("test", scope = user)
        val to = ledger.account("savings", scope = user)

        val (credit, debit) = ledger.transfer(amount, from = from, to = to, code = "bonus", metadata = pairs)

        ledger.lineMetadata(listOf(credit.id, debit.id)).size shouldBe 4
        credit.metadata.shouldBeNull()
        debit.metadata.shouldBeNull()
        checkNotNull(ledger.lines.find(credit.id)).metadata.shouldBeNull()
        checkNotNull(ledger.lines.find(debit.id)).metadata.shouldBeNull()
    }

    private fun facade(database: Database): DoubleEntry =
        SpecAccounts.doubleEntry(database) {
            clock =
                object : Clock {
                    override fun now(): Instant = frozen
                }
        }

    private fun transfer(metadata: Map<String, Any>?): Pair<Line, Line> =
        doubleEntry.transfer(amount, from = test, to = savings, code = "bonus", metadata = metadata)

    /** `LineMetadata.count`. */
    private fun metadataCount(): Long = transaction(database) { LineMetadataTable.selectAll().count() }

    /** `new_metadata`: the rows of the transfer's lines, by id. */
    private fun newMetadata(
        credit: Line,
        debit: Line,
    ): List<LineMetadata> = doubleEntry.lineMetadata(listOf(credit.id, debit.id))

    /**
     * `new_lines.each { |line| line.metadata }`: the metadata of [credit] and [debit] read back, then the stored
     * `metadata` column of each, which the spec shape has.
     */
    private fun attachedMetadata(
        credit: Line,
        debit: Line,
    ): List<Any?> {
        val read = listOf(credit, debit).map { checkNotNull(doubleEntry.lines.find(it.id)).metadata }
        val lines = LinesTable.withJsonMetadata
        val column = checkNotNull(lines.metadata)
        val stored =
            transaction(database) {
                listOf(credit, debit).map { line ->
                    lines
                        .selectAll()
                        .where { lines.id eq line.id }
                        .single()[column]
                }
            }
        return read + stored
    }
}
