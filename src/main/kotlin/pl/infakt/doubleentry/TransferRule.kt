package pl.infakt.doubleentry

/** A transfer rule: money may move from the account [from] to the account [to] under [code] (transfer.rb:66-76). */
data class TransferRule(
    val from: String,
    val to: String,
    val code: String,
)

/**
 * The transfer rules defined so far: the gem's `Transfer::Set` (transfer.rb:26-64). A rule is keyed by its from, its
 * to and its code. [codeMaxLength] is read at each definition, as the gem reads `Transfer.code_max_length`.
 */
class TransferRules internal constructor(
    private val codeMaxLength: () -> Int? = { null },
) {
    private val rules = mutableSetOf<TransferRule>()

    /**
     * Defines the rule [from] → [to] under [code] and returns it (transfer.rb:30-39).
     *
     * @throws TransferCodeTooLongException when [code] has more characters than allowed (transfer.rb:72-75).
     * @throws DuplicateTransferException when the same from, to and code are defined already (transfer.rb:33-34).
     */
    fun define(
        from: String,
        to: String,
        code: String,
    ): TransferRule {
        val maxLength = codeMaxLength()
        // Ruby's String#length counts characters, not UTF-16 units.
        if (maxLength != null && code.codePointCount(0, code.length) > maxLength) {
            throw TransferCodeTooLongException(
                "transfer code '$code' is too long. Please limit it to $maxLength characters.",
            )
        }
        val rule = TransferRule(from, to, code)
        if (!rules.add(rule)) throw DuplicateTransferException()
        return rule
    }

    /** The rule [from] → [to] under [code], or `null` when none is defined (transfer.rb:41-43). */
    fun find(
        from: String,
        to: String,
        code: String?,
    ): TransferRule? = code?.let { TransferRule(from, to, it) }?.takeIf { it in rules }

    /**
     * The rule [from] → [to] under [code] (transfer.rb:45-49).
     *
     * @throws TransferNotAllowedException when none is defined, with the gem's message for identifier
     *   names (X-01 for the rest): Ruby's inspect of
     *   `[from, to, code]` as symbols, such as `[:savings, :cash, :xfer]` (oracle/unknowns.md, U-01).
     */
    fun findOrThrow(
        from: String,
        to: String,
        code: String?,
    ): TransferRule {
        find(from, to, code)?.let { return it }
        val message = listOf(from, to, code).joinToString(", ", prefix = "[", postfix = "]") { inspectSymbol(it) }
        throw TransferNotAllowedException(message)
    }
}

/** A symbol Ruby inspects without quotes: an identifier, optionally ending in `?`, `!` or `=`. */
private val plainSymbol = Regex("[A-Za-z_\\x{80}-\\x{10FFFF}][A-Za-z0-9_\\x{80}-\\x{10FFFF}]*[?!=]?")

/** Ruby's `Symbol#inspect` of [name] (`:name` or `:"two words"`), or `nil` for no name. */
private fun inspectSymbol(name: String?): String =
    when {
        name == null -> "nil"
        plainSymbol.matches(name) -> ":$name"
        else -> ":\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
