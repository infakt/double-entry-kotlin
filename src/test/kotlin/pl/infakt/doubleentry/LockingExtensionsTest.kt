package pl.infakt.doubleentry

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.sql.SQLException
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import pl.infakt.doubleentry.support.TestDatabase

/**
 * locking_extensions_spec.rb: the gem's `ActiveRecord::LockingExtensions`, ported as [LockingExtensions]. The spec's
 * `User.create!` stubs are lambdas that throw, the port has no mocking library (CLAUDE.md); its `users` table is a
 * table of this test, with made-up usernames. The shared examples run once for each adapter's message.
 */
class LockingExtensionsTest {
    private val events = RecordedEvents()

    private val configuration = DoubleEntryConfiguration().apply { events = this@LockingExtensionsTest.events }

    private val extensions = LockingExtensions(configuration)

    init {
        transaction(users) {
            exec("TRUNCATE ${UsersTable.tableName} RESTART IDENTITY")
        }
    }

    @Test
    fun `restartable_transaction keeps running the lock until a RestartTransaction isn't raised`() {
        var calls = 0
        val create = {
            calls++
            if (calls <= 2) throw RestartTransaction()
            true
        }

        shouldNotThrowAny { extensions.restartableTransaction(users) { create() } }

        calls shouldBe 3
    }

    @Test
    fun `with_restart_on_deadlock raises a RestartTransaction error if a deadlock occurs`() {
        adapterMessages.forEach { message ->
            shouldThrow<RestartTransaction> { extensions.withRestartOnDeadlock { throw SQLException(message) } }
        }
    }

    @Test
    fun `with_restart_on_deadlock publishes a notification`() {
        adapterMessages.forEach { message ->
            events.published.clear()
            val exception = SQLException(message)

            shouldThrow<RestartTransaction> { extensions.withRestartOnDeadlock { throw exception } }

            events.published.map { it.first } shouldBe listOf(DEADLOCK_RESTART)
            events.published.single().second shouldBeSameInstanceAs exception
        }
    }

    @Test
    fun `create_ignoring_duplicates does not raise an error if a duplicate index error is raised in the database`() {
        createUser(USERNAME)

        shouldThrow<SQLException> { createUser(USERNAME) }.sqlState shouldBe UNIQUE_VIOLATION
        shouldNotThrowAny { extensions.createIgnoringDuplicates { createUser(USERNAME) } }
    }

    @Test
    fun `create_ignoring_duplicates publishes a notification when a duplicate is encountered`() {
        createUser(USERNAME)

        shouldNotThrowAny { extensions.createIgnoringDuplicates { createUser(USERNAME) } }

        events.published.map { it.first } shouldBe listOf(DUPLICATE_IGNORE)
        val exception = events.published.single().second
        (exception as SQLException).sqlState shouldBe UNIQUE_VIOLATION
    }

    @Test
    fun `create_ignoring_duplicates retries the creation if a deadlock error is raised from the database`() {
        adapterMessages.forEach { message ->
            var calls = 0
            val create = {
                calls++
                if (calls == 1) throw SQLException(message)
                true
            }

            shouldNotThrowAny { extensions.createIgnoringDuplicates(create) }

            calls shouldBe 2
        }
    }

    @Test
    fun `create_ignoring_duplicates publishes a notification on each retry`() {
        adapterMessages.forEach { message ->
            events.published.clear()
            val exception = SQLException(message)
            var calls = 0
            val create = {
                calls++
                if (calls <= 2) throw exception
                true
            }

            shouldNotThrowAny { extensions.createIgnoringDuplicates(create) }

            events.published shouldBe listOf(DEADLOCK_RETRY to exception, DEADLOCK_RETRY to exception)
        }
    }

    @Test
    fun `with_restart_on_deadlock lets any other error through and publishes nothing`() {
        shouldThrowWithMessage<SQLException>(OTHER_ERROR) {
            extensions.withRestartOnDeadlock { throw SQLException(OTHER_ERROR) }
        }

        events.published.shouldBeEmpty()
    }

    @Test
    fun `create_ignoring_duplicates lets any other error through and publishes nothing`() {
        shouldThrowWithMessage<SQLException>(OTHER_ERROR) {
            extensions.createIgnoringDuplicates { throw SQLException(OTHER_ERROR) }
        }

        events.published.shouldBeEmpty()
    }

    @Test
    fun `a deadlock and a duplicate are known by PostgreSQL's SQLState too, as the cause of another error`() {
        val deadlock = RuntimeException(SQLException("ERROR", DEADLOCK_DETECTED))
        val duplicate = RuntimeException(SQLException("ERROR", UNIQUE_VIOLATION))

        shouldThrow<RestartTransaction> { extensions.withRestartOnDeadlock { throw deadlock } }
        var calls = 0
        extensions.createIgnoringDuplicates {
            calls++
            if (calls == 1) throw deadlock
            throw duplicate
        }

        events.published shouldBe
            listOf(DEADLOCK_RESTART to deadlock, DEADLOCK_RETRY to deadlock, DUPLICATE_IGNORE to duplicate)
    }

    @Test
    fun `a deadlock while grabbing the locks restarts the lock's transaction and publishes deadlock_restart`() {
        val doubleEntry = ledger(TestDatabase.ledger(jsonMetadata = false), fixtures = false)
        val account = doubleEntry.account("account_a", scope = "1")
        doubleEntry.lockAccounts(account) {}
        val exception = SQLException(adapterMessages.first())
        var injected: SQLException? = exception
        doubleEntry.locking.beforeGrabLocks = {
            injected?.let {
                injected = null
                throw it
            }
        }
        var ran = 0

        doubleEntry.lockAccounts(account) { ran++ }

        ran shouldBe 1
        events.published shouldBe listOf(DEADLOCK_RESTART to exception)
    }

    @Test
    fun `a deadlock while grabbing the locks inside the caller's transaction goes through and publishes nothing`() {
        val database = TestDatabase.ledger(jsonMetadata = false)
        val doubleEntry = ledger(database, fixtures = true)
        val account = doubleEntry.account("account_a", scope = "1")
        doubleEntry.lockAccounts(account) {}
        val message = adapterMessages.first()
        doubleEntry.locking.beforeGrabLocks = { throw SQLException(message) }

        shouldThrowWithMessage<SQLException>(message) {
            transaction(database) {
                maxAttempts = 1
                doubleEntry.lockAccounts(account) {}
            }
        }

        events.published.shouldBeEmpty()
    }

    @Test
    fun `a deadlock while creating a missing balance record inserts it again and publishes deadlock_retry`() {
        val database = TestDatabase.ledger(jsonMetadata = false)
        val doubleEntry = ledger(database, fixtures = false)
        val account = doubleEntry.account("account_a", scope = "1")
        val exception = SQLException(adapterMessages.first())
        var injected: SQLException? = exception
        doubleEntry.locking.beforeInsertBalance = {
            injected?.let {
                injected = null
                throw it
            }
        }
        var ran = 0

        doubleEntry.lockAccounts(account) { ran++ }

        ran shouldBe 1
        events.published shouldBe listOf(DEADLOCK_RETRY to exception)
        AccountBalances(database, doubleEntry.configuration).findByAccount(account)?.balance shouldBe
            Money.zero(account.currency)
    }

    @Test
    fun `a balance record created meanwhile by another transaction is ignored and publishes duplicate_ignore`() {
        val database = TestDatabase.ledger(jsonMetadata = false)
        val doubleEntry = ledger(database, fixtures = false)
        val account = doubleEntry.account("account_a", scope = "1")
        val balances = AccountBalances(database, doubleEntry.configuration)
        var otherTransaction = true
        doubleEntry.locking.beforeInsertBalance = { locked ->
            if (otherTransaction) {
                otherTransaction = false
                balances.insert(locked, Money.zero(locked.currency))
            }
        }
        var ran = 0

        doubleEntry.lockAccounts(account) { ran++ }

        ran shouldBe 1
        events.published.map { it.first } shouldBe listOf(DUPLICATE_IGNORE)
        generateSequence(events.published.single().second) { it.cause }
            .filterIsInstance<SQLException>()
            .map { it.sqlState }
            .toList() shouldContain UNIQUE_VIOLATION
        balances.findByAccount(account).shouldNotBeNull()
    }

    @Test
    fun `the default events log each event at INFO and its exception at DEBUG, as the gem's LogSubscriber does`() {
        val exception = SQLException(adapterMessages.first())

        val records =
            logged(DoubleEntryEvents::class.java.name) {
                DoubleEntryEvents.Logging.deadlockRestart(exception)
                DoubleEntryEvents.Logging.deadlockRetry(exception)
                DoubleEntryEvents.Logging.duplicateIgnored(exception)
            }

        // System.Logger's INFO and DEBUG are java.util.logging's INFO and FINE.
        records.map { Triple(it.level, it.message, it.thrown) } shouldBe
            listOf("Deadlock causing restart", "Deadlock causing retry", "Duplicate ignored").flatMap { message ->
                listOf(Triple(Level.INFO, message, null), Triple(Level.FINE, exception.toString(), exception))
            }
    }

    /**
     * The records [block] logs to the `java.util.logging` logger [name]: the backend the JDK's default
     * [System.LoggerFinder] gives a [System.Logger] when no other is on the classpath, as in these tests.
     */
    private fun logged(
        name: String,
        block: () -> Unit,
    ): List<LogRecord> {
        val logger = Logger.getLogger(name)
        val records = mutableListOf<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        val level = logger.level
        val useParentHandlers = logger.useParentHandlers
        logger.level = Level.ALL
        logger.useParentHandlers = false
        logger.addHandler(handler)
        try {
            block()
        } finally {
            logger.removeHandler(handler)
            logger.level = level
            logger.useParentHandlers = useParentHandlers
        }
        return records
    }

    /** A facade on [database] with one scoped account of locking_spec.rb:16-39, telling this test's events. */
    private fun ledger(
        database: Database,
        fixtures: Boolean,
    ): DoubleEntry =
        DoubleEntry(database) {
            runningInsideTransactionalFixtures = fixtures
            events = this@LockingExtensionsTest.events
            accounts { define("account_a", scopeIdentifier = { it }) }
        }

    /** `User.create!(username:)` on this test's `users` table. */
    private fun createUser(username: String): Long =
        transaction(users) {
            maxAttempts = 1
            UsersTable.insertAndGetId { it[UsersTable.username] = username }.value
        }

    /** The events published, each as its gem name without `.double_entry` and its exception, in order. */
    private class RecordedEvents : DoubleEntryEvents {
        val published = mutableListOf<Pair<String, Throwable>>()

        override fun deadlockRestart(exception: Throwable) {
            published += DEADLOCK_RESTART to exception
        }

        override fun deadlockRetry(exception: Throwable) {
            published += DEADLOCK_RETRY to exception
        }

        override fun duplicateIgnored(exception: Throwable) {
            published += DUPLICATE_IGNORE to exception
        }
    }

    /** spec/support/schema.rb:58-63's `users`, without the timestamps the examples do not read. */
    private object UsersTable : LongIdTable("users") {
        val username = varchar("username", USERNAME_LENGTH).uniqueIndex("index_users_on_username")
    }

    private companion object {
        const val USERNAME_LENGTH = 255

        const val USERNAME = "user_a"

        const val OTHER_ERROR = "some other error"

        const val DEADLOCK_RESTART = "deadlock_restart"

        const val DEADLOCK_RETRY = "deadlock_retry"

        const val DUPLICATE_IGNORE = "duplicate_ignore"

        const val DEADLOCK_DETECTED = "40P01"

        const val UNIQUE_VIOLATION = "23505"

        /** locking_extensions_spec.rb:4-6: the deadlock messages of PostgreSQL, MySQL and SQLite. */
        val adapterMessages =
            listOf(
                "PG::Error: ERROR:  deadlock detected",
                "Mysql::Error: Deadlock found when trying to get lock",
                "SQLite3::BusyException: database is locked: UPDATE...",
            )

        /** A database of these tests, with only the `users` table. */
        val users: Database by lazy {
            TestDatabase.empty().also { database -> transaction(database) { SchemaUtils.create(UsersTable) } }
        }
    }
}
