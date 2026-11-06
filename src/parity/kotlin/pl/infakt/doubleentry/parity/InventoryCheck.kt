package pl.infakt.doubleentry.parity

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Checks `oracle/inventory.json` against `oracle/index.json` and `oracle/enabled.json` (oracle/README.md, "Inventory").
 * Each problem is a line `inventory <name>: expected <x>, got <y>`, where the name is the item's id, or the file's
 * name for a file that cannot be read. The replay counts these lines as mismatches.
 */
object InventoryCheck {
    /** The statuses an item may have. A `pending` item is one a later stage records. */
    val statuses = setOf("verified", "inferred", "excluded", "pending")

    /**
     * Every problem of the inventory under [oracle]:
     * - a status that is not one of [statuses];
     * - an excluded item (one with an `excluded` key or the status `excluded`) without a reason;
     * - an item neither excluded nor pending that names no scenario;
     * - a scenario that `index.json` does not list;
     * - a pending item when every area of `enabled.json` is on (strict mode).
     */
    fun check(oracle: Path): List<String> {
        val problems = mutableListOf<String>()
        val inventory = read(oracle, "inventory.json", problems)
        val index = read(oracle, "index.json", problems)
        if (inventory == null || index == null) return problems
        val scenarios =
            array(index, "scenarios")
                .mapNotNull { text((it as? JsonObject)?.get("id")) }
                .toSet()
        val strict = everyAreaOn(oracle)
        val items = (inventory as? JsonObject)?.get("items")
        if (items !is JsonArray) {
            problems += "inventory inventory.json: expected an array at $.items, got ${show(items)}"
            return problems
        }
        items.forEachIndexed { position, item -> problems += checkItem(position, item, scenarios, strict) }
        return problems
    }

    private fun checkItem(
        position: Int,
        item: JsonElement,
        scenarios: Set<String>,
        strict: Boolean,
    ): List<String> {
        if (item !is JsonObject) return listOf("inventory $.items[$position]: expected an object, got $item")
        val name = text(item["id"]) ?: "$.items[$position]"
        val problems = mutableListOf<String>()
        val report = { expected: String, got: String -> problems += "inventory $name: expected $expected, got $got" }
        val status = text(item["status"])
        if (status == null || status !in statuses) {
            report("a status of ${statuses.joinToString()}", show(item["status"]))
        }
        val excluded = "excluded" in item || status == "excluded"
        if (excluded && text(item["excluded"]).isNullOrBlank()) report("an excluded reason", show(item["excluded"]))
        val named = array(item, "scenarios")
        if (!excluded && status != "pending" && named.isEmpty()) report("a scenario or an excluded reason", "none")
        named
            .filterNot { scenario -> text(scenario)?.let { it in scenarios } == true }
            .forEach { report("a scenario of index.json", it.toString()) }
        if (strict && status == "pending") report("no pending item when every area is on", "\"pending\"")
        return problems
    }

    /** Whether every area of `enabled.json` is on: then no item may still be pending. */
    private fun everyAreaOn(oracle: Path): Boolean {
        val file = oracle.resolve("enabled.json")
        if (!file.exists()) return false
        val areas = (Json.parseToJsonElement(file.readText()) as? JsonObject)?.get("areas") as? JsonObject
        return !areas.isNullOrEmpty() && areas.values.all { it == JsonPrimitive("on") }
    }

    private fun read(
        oracle: Path,
        name: String,
        problems: MutableList<String>,
    ): JsonElement? {
        val file = oracle.resolve(name)
        if (!file.exists()) {
            problems += "inventory $name: expected a file, got $MISSING"
            return null
        }
        return try {
            Json.parseToJsonElement(file.readText())
        } catch (error: SerializationException) {
            problems += "inventory $name: expected JSON, got ${error.message}"
            null
        }
    }

    private fun array(
        element: JsonElement,
        key: String,
    ): List<JsonElement> = ((element as? JsonObject)?.get(key) as? JsonArray).orEmpty()

    private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun show(element: JsonElement?): String = element?.toString() ?: MISSING
}
