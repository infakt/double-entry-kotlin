package pl.infakt.doubleentry

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Checks the table of docs/parity.md against the gem's specs under `reference/double_entry/spec/`, the test items of
 * `oracle/inventory.json` and the tests the table names.
 */
class ParityTableTest {
    private val table: List<List<String>> =
        Path
            .of("docs/parity.md")
            .readLines()
            .filter { it.startsWith("|") }
            .map { cells(it) }

    private val rows: List<List<String>> = table.drop(2)

    private val rowExamples: Set<String> = rows.map { it[EXAMPLE] }.toSet()

    /** The examples of the gem's specs, `spec/...rb:line`; a shared example once per `it_behaves_like` context. */
    private val specExamples: Set<String> =
        Files.walk(specRoot).use { paths ->
            paths
                .filter { it.fileName.toString().endsWith("_spec.rb") }
                .toList()
                .flatMap { examplesOf(it) }
                .toSet()
        }

    /** The test items of the inventory, as the table keys them: the source, and the context of a shared example. */
    private val inventoryExamples: Set<String> =
        Json
            .parseToJsonElement(Path.of("oracle/inventory.json").readText())
            .jsonObject
            .getValue("items")
            .jsonArray
            .map { it.jsonObject }
            .filter { it["kind"]?.jsonPrimitive?.content == "test" }
            .map { item ->
                val source = item.getValue("source").jsonPrimitive.content
                val context = contextSuffix.find(item.getValue("id").jsonPrimitive.content)?.value.orEmpty()
                source + context
            }.toSet()

    @Test
    fun `the table has the agreed columns and rows of five cells`() {
        table.first() shouldBe
            listOf(
                "gem example (file:line)",
                "description",
                "test (Class.`name`)",
                "status (ported/excluded/pending)",
                "note",
            )
        rows.shouldNotBeEmpty()
        rows.filter { it.size != COLUMNS } shouldBe emptyList()
    }

    @Test
    fun `every row names a gem example once and has a known status`() {
        rows.filterNot { example.matches(it[EXAMPLE]) && it[STATUS] in statuses } shouldBe emptyList()
        rows.groupBy { it[EXAMPLE] }.filterValues { it.size > 1 }.keys shouldBe emptySet()
    }

    @Test
    fun `every ported row names an existing test method`() {
        rows.filter { it[STATUS] == "ported" && !testExists(it[TEST]) }.map { it[TEST] } shouldBe emptyList()
    }

    @Test
    fun `every excluded row has a note`() {
        rows.filter { it[STATUS] == "excluded" && it[NOTE].isBlank() }.map { it[EXAMPLE] } shouldBe emptyList()
    }

    @Test
    fun `the shared examples count once for each context that behaves like them`() {
        val file = "spec/active_record/locking_extensions_spec.rb"
        val plain = listOf(9, 53, 60).map { "$file:$it" }
        val shared = listOf(20, 25, 71, 78).flatMap { line -> adapters.map { "$file:$line ($it)" } }

        specExamples.filter { it.startsWith("$file:") }.toSet() shouldBe (plain + shared).toSet()
    }

    @Test
    fun `every example of the gem's specs has a row`() {
        specExamples.shouldNotBeEmpty()
        (specExamples - rowExamples).sorted() shouldBe emptyList()
    }

    @Test
    fun `every row matches an example of the gem's specs`() {
        (rowExamples - specExamples).sorted() shouldBe emptyList()
    }

    @Test
    fun `no row is pending`() {
        rows.filter { it[STATUS] == "pending" }.map { it[EXAMPLE] } shouldBe emptyList()
    }

    @Test
    fun `every test item of the inventory has a row`() {
        inventoryExamples.shouldNotBeEmpty()
        (inventoryExamples - rowExamples).sorted() shouldBe emptyList()
    }

    private fun cells(line: String): List<String> =
        line
            .trim()
            .removePrefix("|")
            .removeSuffix("|")
            .split("|")
            .map { it.trim() }

    /** Whether [reference], `` Class.`name` ``, names a `@Test` method of a class under `pl.infakt.doubleentry`. */
    private fun testExists(reference: String): Boolean {
        val (className, method) = testReference.matchEntire(reference)?.destructured ?: return false
        val type = runCatching { Class.forName("pl.infakt.doubleentry.$className") }.getOrNull() ?: return false
        return type.declaredMethods.any { it.name == method && it.isAnnotationPresent(Test::class.java) }
    }

    /**
     * The examples of [file]: each line that starts with `it`, `specify` or `its`, keyed `spec/...rb:line`. An example
     * of a `shared_examples` block that some `it_behaves_like` uses is keyed once for each use, with the name of the
     * context around the use: `spec/...rb:line (context)`. A block only `include_examples` uses keeps the plain key.
     */
    private fun examplesOf(file: Path): List<String> {
        val name = "spec/${specRoot.relativize(file).invariantSeparatorsPathString}"
        val lines = file.readLines()
        val blocks = sharedBlocks(lines)
        val uses =
            lines.indices.mapNotNull { index ->
                val use = itBehavesLike.find(lines[index]) ?: return@mapNotNull null
                val block = blocks.lastOrNull { it.name == use.groupValues[1] && it.start < index }
                block?.let { Use(it, contextOf(lines, index)) }
            }
        return lines
            .indices
            .filter { exampleLine.containsMatchIn(lines[it]) }
            .flatMap { index ->
                val key = "$name:${index + 1}"
                val contexts = uses.filter { index > it.block.start && index < it.block.end }.map { it.context }
                if (contexts.isEmpty()) listOf(key) else contexts.map { "$key ($it)" }
            }
    }

    /** The `shared_examples` blocks of [lines]: each from its opening line to the `end` at the same indent. */
    private fun sharedBlocks(lines: List<String>): List<SharedBlock> =
        lines.indices.mapNotNull { start ->
            val opening = sharedExamples.find(lines[start]) ?: return@mapNotNull null
            val closing = "${opening.groupValues[1]}end"
            val end = (start + 1 until lines.size).firstOrNull { lines[it].trimEnd() == closing } ?: lines.size
            SharedBlock(opening.groupValues[2], start, end)
        }

    /** The name of the nearest `context` or `describe` above line [index] of [lines] that encloses it. */
    private fun contextOf(
        lines: List<String>,
        index: Int,
    ): String {
        val indent = lines[index].takeWhile { it == ' ' }.length
        val enclosing =
            (index - 1 downTo 0).firstNotNullOfOrNull { above ->
                contextLine.find(lines[above])?.takeIf { it.groupValues[1].length < indent }
            }
        return enclosing?.groupValues?.get(2).orEmpty()
    }

    private data class SharedBlock(
        val name: String,
        val start: Int,
        val end: Int,
    )

    private data class Use(
        val block: SharedBlock,
        val context: String,
    )

    private companion object {
        const val COLUMNS = 5
        const val EXAMPLE = 0
        const val TEST = 2
        const val STATUS = 3
        const val NOTE = 4

        val specRoot: Path = Path.of("reference/double_entry/spec")
        val statuses = setOf("ported", "excluded", "pending")
        val example = Regex("spec/[\\w/]+\\.rb:\\d+( \\(\\w+\\))?")
        val testReference = Regex("([A-Za-z_][\\w.]*)\\.`([^`]+)`")
        val contextSuffix = Regex(" \\(\\w+\\)$")
        val exampleLine = Regex("^\\s*(?:it|specify|its)\\b")
        val itBehavesLike = Regex("^\\s*it_behaves_like\\s+['\"]([^'\"]+)['\"]")
        val sharedExamples = Regex("^(\\s*)(?:RSpec\\.)?shared_examples\\s+['\"]([^'\"]+)['\"]")
        val contextLine = Regex("^(\\s*)(?:RSpec\\.)?(?:context|describe)\\s+['\"]([^'\"]+)['\"]")

        /** The contexts of locking_extensions_spec.rb that behave like its shared examples. */
        val adapters = listOf("mysql", "postgres", "sqlite")
    }
}
