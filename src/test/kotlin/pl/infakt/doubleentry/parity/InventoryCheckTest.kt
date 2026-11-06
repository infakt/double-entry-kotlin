package pl.infakt.doubleentry.parity

import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.writeText
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InventoryCheckTest {
    @TempDir
    lateinit var oracle: Path

    @Test
    fun `a sound inventory has no problems`() {
        write(
            """{"id": "api:a", "status": "verified", "scenarios": ["area-one"]}""",
            """{"id": "api:b", "status": "inferred", "scenarios": [], "excluded": "Not ported."}""",
            """{"id": "api:c", "status": "pending", "scenarios": []}""",
        )

        InventoryCheck.check(oracle) shouldBe emptyList()
    }

    @Test
    fun `a scenario item that names an id missing from the index is reported`() {
        write("""{"id": "api:a", "status": "verified", "scenarios": ["area-one", "area-two"]}""")

        InventoryCheck.check(oracle) shouldBe
            listOf("inventory api:a: expected a scenario of index.json, got \"area-two\"")
    }

    @Test
    fun `a scenario item that names no scenario is reported`() {
        write("""{"id": "api:a", "status": "verified", "scenarios": []}""")

        InventoryCheck.check(oracle) shouldBe
            listOf("inventory api:a: expected a scenario or an excluded reason, got none")
    }

    @Test
    fun `an excluded item without a reason is reported`() {
        write(
            """{"id": "api:a", "status": "inferred", "scenarios": [], "excluded": " "}""",
            """{"id": "api:b", "status": "excluded", "scenarios": []}""",
        )

        InventoryCheck.check(oracle) shouldBe
            listOf(
                "inventory api:a: expected an excluded reason, got \" \"",
                "inventory api:b: expected an excluded reason, got <missing>",
            )
    }

    @Test
    fun `an unknown status is reported`() {
        write("""{"id": "api:a", "status": "guessed", "scenarios": ["area-one"]}""")

        InventoryCheck.check(oracle) shouldBe
            listOf("inventory api:a: expected a status of verified, inferred, excluded, pending, got \"guessed\"")
    }

    @Test
    fun `a pending item is reported when every area is on`() {
        write("""{"id": "api:c", "status": "pending", "scenarios": []}""", enabled = """{"areas": {"area": "on"}}""")

        InventoryCheck.check(oracle) shouldBe
            listOf("inventory api:c: expected no pending item when every area is on, got \"pending\"")
    }

    @Test
    fun `a missing inventory is reported`() {
        oracle.resolve("index.json").writeText("""{"scenarios": []}""")

        InventoryCheck.check(oracle) shouldBe listOf("inventory inventory.json: expected a file, got <missing>")
    }

    private fun write(
        vararg items: String,
        enabled: String = """{"areas": {"area": "on", "other": "off"}}""",
    ) {
        oracle.resolve("enabled.json").writeText(enabled)
        oracle.resolve("index.json").writeText("""{"scenarios": [{"id": "area-one", "area": "area"}]}""")
        oracle.resolve("inventory.json").writeText("""{"items": [${items.joinToString(",\n")}]}""")
    }
}
