package pl.infakt.doubleentry.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.StringColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.json.jsonb

/**
 * The type of the gem's `t.string` columns on PostgreSQL: `character varying` without a length, as Active Record's
 * PostgreSQL adapter makes a `t.string` without a `limit` (its `varchar(255)` is MySQL's).
 */
private class UnboundedVarCharColumnType : StringColumnType() {
    override fun sqlType(): String = "VARCHAR"
}

/** A `t.string` column [name] of the migration. */
private fun Table.string(name: String): Column<String> = registerColumn(name, UnboundedVarCharColumnType())

/**
 * `double_entry_account_balances` (migration.rb:3-11): the balance of each account and scope, the table of
 * `DoubleEntry::AccountBalance` (account_balance.rb).
 */
internal object AccountBalancesTable : LongIdTable("double_entry_account_balances") {
    val account = string("account")
    val scope = string("scope").nullable()
    val balance = long("balance")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        index("index_account_balances_on_account", false, account)
        uniqueIndex("index_account_balances_on_scope_and_account", scope, account)
    }
}

/**
 * `double_entry_lines` (migration.rb:13-37): one line per account of a transfer, the table of `DoubleEntry::Line`
 * (line.rb). With json metadata the lines have a nullable `jsonb` [metadata] column, as the migration adds on
 * PostgreSQL (migration.rb:23-29); without, [metadata] is null and the metadata goes to [LineMetadataTable].
 */
internal class LinesTable private constructor(
    jsonMetadata: Boolean,
) : LongIdTable("double_entry_lines") {
    val account = string("account")
    val scope = string("scope").nullable()
    val code = string("code")
    val amount = long("amount")
    val balance = long("balance")
    val partnerId = long("partner_id").nullable()
    val partnerAccount = string("partner_account")
    val partnerScope = string("partner_scope").nullable()
    val detailType = string("detail_type").nullable()
    val detailId = long("detail_id").nullable()

    // Declared here, before the timestamps, so the column has the migration's place in the table.
    val metadata: Column<JsonObject?>? =
        if (jsonMetadata) {
            jsonb<JsonObject>(
                "metadata",
                { value -> value.toString() },
                { text -> Json.parseToJsonElement(text).jsonObject },
            ).nullable()
        } else {
            null
        }

    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        index("lines_account_code_created_at_idx", false, account, code, createdAt)
        index("lines_account_created_at_idx", false, account, createdAt)
        index("lines_scope_account_created_at_idx", false, scope, account, createdAt)
        index("lines_scope_account_id_idx", false, scope, account, id)
    }

    companion object {
        /** The lines with the `metadata` column: the gem's `json_metadata` true. */
        val withJsonMetadata = LinesTable(jsonMetadata = true)

        /** The lines without it, their metadata in [LineMetadataTable]: the gem's `json_metadata` false. */
        val withMetadataTable = LinesTable(jsonMetadata = false)

        /** The lines of the shape [jsonMetadata] selects. */
        fun of(jsonMetadata: Boolean): LinesTable = if (jsonMetadata) withJsonMetadata else withMetadataTable
    }
}

/**
 * `double_entry_line_checks` (migration.rb:39-46): the runs of the line check, the table of
 * `DoubleEntry::Validation::LineCheck` (validation/line_check.rb).
 */
internal object LineChecksTable : LongIdTable("double_entry_line_checks") {
    val lastLineId = long("last_line_id")
    val errorsFound = bool("errors_found")
    val log = text("log").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        index("line_checks_created_at_last_line_id_idx", false, createdAt, lastLineId)
    }
}

/**
 * `double_entry_line_metadata` (migration.rb:47-55): the lines' metadata as key and value rows, the table of
 * `DoubleEntry::LineMetadata` (line_metadata.rb). Only without json metadata.
 */
internal object LineMetadataTable : LongIdTable("double_entry_line_metadata") {
    val lineId = long("line_id")
    val key = string("key")
    val value = string("value")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        index("lines_meta_line_id_key_value_idx", false, lineId, key, value)
    }
}
