package pl.infakt.doubleentry

import kotlin.time.Instant

/**
 * What a line is about: the gem's polymorphic `detail` of a line (line.rb, `belongs_to :detail, polymorphic: true`),
 * stored as `detail_type` and `detail_id`. [type] is the name of the record's type, such as `Invoice`, and [id] its id.
 */
data class Detail(
    val type: String,
    val id: Long,
)

/**
 * A row of `double_entry_lines`: the gem's `DoubleEntry::Line` (line.rb), one side of a transfer. [amount] and
 * [balance] are in the currency of [account], as the gem's readers make them; [balance] is the account's balance after
 * the line. [partnerId] is the id of the other side's line and [partnerAccount] its account.
 *
 * A line read from the database has [account] and [partnerAccount] as the configuration's accounts of the stored
 * identifiers with the stored scope identities, as the gem's readers make them (line.rb:106-108, 121-123).
 *
 * [metadata] is the line's `metadata` json column, written and read only with [DoubleEntryConfiguration.jsonMetadata]:
 * the metadata of the transfer, on both of its lines (transfer.rb:112), or `null` for a transfer without metadata. It
 * is a JSON object as plain Kotlin values, a map of string keys to values that are each a `String`, a `Long` for a
 * whole number or a `Double` for any other number, a `Boolean`, `null`, a `List` of such values or a `Map` of string
 * keys to them; `mapOf("country" to "AU", "tax" to listOf("GST", "VAT"))` reads back as itself. A transfer's metadata
 * is stored in that form: any other [Number] as a JSON number (so an `Int` reads back as a `Long`, and a `Double` that
 * is not finite as `null`), an [Iterable] or an [Array] as a list, the keys of a [Map] as their `toString()`, and any
 * other value as its `toString()`. A line a transfer returns has its metadata in the form it reads back as.
 * Without json metadata it is `null`, also on a database whose lines have the column; the gem's `Line#metadata` then
 * reads the column on such a database and raises `NoMethodError` on the generator's (docs/deviations.md, X-04). The
 * metadata without json metadata is in `double_entry_line_metadata`, read by [DoubleEntry.lineMetadata].
 */
data class Line(
    val id: Long,
    val account: AccountInstance,
    val partnerAccount: AccountInstance,
    val code: String,
    val amount: Money,
    val balance: Money,
    val partnerId: Long?,
    val detail: Detail?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val metadata: Map<String, Any?>? = null,
) {
    /** Whether the line takes money out of its account, [amount] below zero: `decrease?` (line.rb:137-139). */
    val isDecrease: Boolean
        get() = amount.isNegative

    /** Whether the line puts money into its account, [amount] above zero: `increase?` (line.rb:141-143). */
    val isIncrease: Boolean
        get() = amount.isPositive
}
