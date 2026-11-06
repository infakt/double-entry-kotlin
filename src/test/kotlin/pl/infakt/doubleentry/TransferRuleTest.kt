package pl.infakt.doubleentry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class TransferRuleTest {
    private val configuration = DoubleEntryConfiguration()

    private val set = TransferRules()

    init {
        set.define(from = "from", to = "to", code = "transfer_code")
        set.define(from = "from", to = "to", code = "another_transfer_code")
    }

    @Test
    fun `given a code_max_length of 47, a code 47 characters in length is accepted`() {
        configuration.codeMaxLength = 47
        val code = "xxxxxxxxxxxxxxxx 47 characters xxxxxxxxxxxxxxxx"

        configuration.transfers.define("from", "to", code) shouldBe TransferRule("from", "to", code)
    }

    @Test
    fun `given a code_max_length of 47, a code 48 characters in length raises TransferCodeTooLongException`() {
        configuration.codeMaxLength = 47
        val code = "xxxxxxxxxxxxxxxx 48 characters xxxxxxxxxxxxxxxxx"

        val error = shouldThrow<TransferCodeTooLongException> { configuration.transfers.define("from", "to", code) }

        error.message shouldContain "'$code'"
        error.message shouldBe "transfer code '$code' is too long. Please limit it to 47 characters."
    }

    @Test
    fun `the code length is counted in characters, not in UTF-16 units`() {
        configuration.codeMaxLength = 1

        configuration.transfers.define("from", "to", "𝄞") shouldBe TransferRule("from", "to", "𝄞")
    }

    @Test
    fun `find finds the transfers`() {
        set.find("from", "to", "transfer_code") shouldBe TransferRule("from", "to", "transfer_code")
        set.find("from", "to", "another_transfer_code") shouldBe TransferRule("from", "to", "another_transfer_code")
    }

    @Test
    fun `find returns nothing when searching for undefined transfers`() {
        set.find("to", "from", "transfer_code") shouldBe null
    }

    @Test
    fun `find returns nothing for no code`() {
        set.find("from", "to", null) shouldBe null
    }

    @Test
    fun `findOrThrow also finds the transfers`() {
        set.findOrThrow("from", "to", "transfer_code") shouldBe TransferRule("from", "to", "transfer_code")
        set.findOrThrow("from", "to", "another_transfer_code") shouldBe
            TransferRule("from", "to", "another_transfer_code")
    }

    @Test
    fun `findOrThrow raises an error when searching for undefined transfers`() {
        val error = shouldThrow<TransferNotAllowedException> { set.findOrThrow("to", "from", "transfer_code") }

        error.message shouldBe "[:to, :from, :transfer_code]"
    }

    @Test
    fun `the TransferNotAllowed message quotes a name that is no plain symbol and shows no code as nil`() {
        val error = shouldThrow<TransferNotAllowedException> { set.findOrThrow("savings account", "gah!", null) }

        error.message shouldBe """[:"savings account", :gah!, nil]"""
    }

    @Test
    fun `the TransferNotAllowed message escapes quotes and backslashes in a quoted name`() {
        val error = shouldThrow<TransferNotAllowedException> { set.findOrThrow("a\"b\\c", "to", "code") }

        error.message shouldBe """[:"a\"b\\c", :to, :code]"""
    }

    @Test
    fun `the TransferNotAllowed message quotes operator and instance variable names (X-01)`() {
        val error = shouldThrow<TransferNotAllowedException> { set.findOrThrow("+", "@a", "code") }

        error.message shouldBe """[:"+", :"@a", :code]"""
    }

    @Test
    fun `defining the same from, to and code twice raises DuplicateTransferException`() {
        shouldThrow<DuplicateTransferException> {
            configuration.transfers {
                define(from = "savings", to = "cash", code = "xfer")
                define(from = "savings", to = "cash", code = "xfer")
            }
        }
    }
}
