package pl.infakt.doubleentry

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.readText
import org.junit.jupiter.api.Test

/** Checks the deviations of docs/deviations.md against the form the file asks for and the tests they name (AC20). */
class DeviationsTest {
    /** The lines of the file outside its fenced code blocks, where the template of a deviation is. */
    private val lines: List<String> =
        Path
            .of("docs/deviations.md")
            .readLines()
            .fold(Pair(false, emptyList<String>())) { (fenced, kept), line ->
                when {
                    line.trimStart().startsWith("```") -> Pair(!fenced, kept)
                    fenced -> Pair(true, kept)
                    else -> Pair(false, kept + line)
                }
            }.second

    private val deviations: List<Deviation> =
        lines
            .withIndex()
            .filter { it.value.startsWith("## ") }
            .map { (index, heading) ->
                val body = lines.drop(index + 1).takeWhile { !it.startsWith("## ") }
                Deviation(heading, body.filter { it.startsWith("- ") })
            }

    @Test
    fun `every section is a deviation with an id in order and a title`() {
        deviations.shouldNotBeEmpty()
        deviations.filterNot { headingPattern.matches(it.heading) }.map { it.heading } shouldBe emptyList()
        val ids = deviations.mapNotNull {
            headingPattern
                .matchEntire(it.heading)
                ?.groupValues
                ?.get(1)
                ?.toInt()
        }
        ids shouldBe ids.distinct().sorted()
    }

    @Test
    fun `every deviation has its Gem, Port, Decision and Test lines, in that order`() {
        deviations
            .filterNot { deviation ->
                deviation.items.map { it.substringBefore(":") } == labels &&
                    deviation.items.all { it.substringAfter(":").isNotBlank() }
            }.map { it.heading } shouldBe emptyList()
    }

    @Test
    fun `every deviation names an existing test method`() {
        deviations
            .mapNotNull { deviation -> deviation.items.firstOrNull { it.startsWith("- Test:") } }
            .map { it.removePrefix("- Test:").trim() }
            .filterNot { testExists(it) } shouldBe emptyList()
    }

    @Test
    fun `every deviation the parity table names is in the file`() {
        val ids = deviations.mapNotNull { headingPattern.matchEntire(it.heading)?.groupValues?.get(1) }.toSet()
        val named =
            deviationReference
                .findAll(Path.of("docs/parity.md").readText())
                .map { it.groupValues[1] }
                .toSet()

        named.shouldNotBeEmpty()
        (named - ids).sorted().map { "X-$it" } shouldBe emptyList()
    }

    /** Whether [reference], `` Class.`name` ``, names a `@Test` method of a class under `pl.infakt.doubleentry`. */
    private fun testExists(reference: String): Boolean {
        val (className, method) = testReference.matchEntire(reference)?.destructured ?: return false
        val type = runCatching { Class.forName("pl.infakt.doubleentry.$className") }.getOrNull() ?: return false
        return type.declaredMethods.any { it.name == method && it.isAnnotationPresent(Test::class.java) }
    }

    private data class Deviation(
        val heading: String,
        val items: List<String>,
    )

    private companion object {
        val headingPattern = Regex("## X-(\\d{2}): \\S.*")
        val labels = listOf("- Gem", "- Port", "- Decision", "- Test")
        val testReference = Regex("([A-Za-z_][\\w.]*)\\.`([^`]+)`")

        /** A reference to a deviation, `X-<nn>`. */
        val deviationReference = Regex("\\bX-(\\d{2})\\b")
    }
}
