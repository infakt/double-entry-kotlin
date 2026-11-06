package pl.infakt.doubleentry.parity

import kotlinx.serialization.json.JsonObject

/**
 * The metadata-json area: the ops and the schema shapes of [MetadataTableExecutor] (oracle/README.md, "Metadata"),
 * whose scenarios set `json_metadata` with `set_config` before they transfer, on the spec shape. With it, a transfer's
 * `metadata` is the lines' `metadata` column, which `line_metadata_of` reads back and the effects list with the lines,
 * and `line_metadata_count` stays at the table's rows, none.
 */
object MetadataJsonExecutor : AreaExecutor {
    override fun start(input: JsonObject): ScenarioRun = MetadataTableExecutor.start(input)
}
