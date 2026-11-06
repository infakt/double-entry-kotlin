package pl.infakt.doubleentry.benchmark

import java.util.Currency
import java.util.Locale
import pl.infakt.doubleentry.Money
import pl.infakt.doubleentry.support.SpecAccounts
import pl.infakt.doubleentry.support.TestDatabase
import pl.infakt.doubleentry.support.TestUser

/**
 * spec/performance/double_entry_performance_spec.rb: times 100 transfers of $10.00 from a user's `test` account to
 * their `savings` account under `bonus`, in three profiles, and prints `<profile> Time: <s>s` for each, as
 * `PerformanceHelper#stop_profiling` does (spec/support/performance_helper.rb:9-11).
 *
 * Each profile runs on the spec shape (TestDatabase.specLedger), emptied before it, as each example of the spec
 * starts on an empty database. The time is the wall-clock time of the 100 transfers; the facade and the accounts are
 * made before it starts. Not a test: `./gradlew benchmark` runs it (build.gradle.kts).
 */
object TransferBenchmark {
    private const val TRANSFERS = 100

    private const val METADATA_KEYS = 8

    private const val NANOS_PER_SECOND = 1_000_000_000.0

    /** `Money.new(10_00)` (double_entry_performance_spec.rb:6). */
    private val amount = Money(10_00, Currency.getInstance("USD"))

    /** `big_metadata` (double_entry_performance_spec.rb:16-17, 24-25): `key0` to `key7`, valued `value0` to `value7`. */
    private val bigMetadata: Map<String, Any> = (0 until METADATA_KEYS).associate { "key$it" to "value$it" }

    @JvmStatic
    fun main(args: Array<String>) {
        // double_entry_performance_spec.rb:10: without metadata.
        profile("transfer", metadata = null, json = false)
        // double_entry_performance_spec.rb:15: with metadata, in the separate metadata table.
        profile("transfer-with-metadata-table", bigMetadata, json = false)
        // double_entry_performance_spec.rb:22: with metadata, in the metadata column of the lines (json_metadata).
        profile("transfer-with-metadata-column", bigMetadata, json = true)
    }

    /** `profile_transfers_with_metadata` (double_entry_performance_spec.rb:32-39). */
    private fun profile(
        name: String,
        metadata: Map<String, Any>?,
        json: Boolean,
    ) {
        val doubleEntry = SpecAccounts.doubleEntry(TestDatabase.specLedger()) { jsonMetadata = json }
        val user = TestUser(1)
        val test = doubleEntry.account("test", scope = user)
        val savings = doubleEntry.account("savings", scope = user)

        val start = System.nanoTime()
        repeat(TRANSFERS) {
            doubleEntry.transfer(amount, from = test, to = savings, code = "bonus", metadata = metadata)
        }
        val seconds = (System.nanoTime() - start) / NANOS_PER_SECOND

        println("$name Time: ${threeSignificantDigits(seconds)}s")
    }

    /**
     * [seconds] as Ruby's `format('%#.3g', seconds)` writes it: three significant digits, trailing zeros kept, and
     * the decimal point kept even without a digit after it (`123.`). Java's `%g` takes no `#` flag but otherwise
     * agrees with C's: decimal notation from 10^-4 up to 10^3, scientific outside.
     */
    private fun threeSignificantDigits(seconds: Double): String {
        val text = String.format(Locale.ROOT, "%.3g", seconds)
        return if ('.' in text || 'e' in text) text else "$text."
    }
}
