package pl.infakt.doubleentry.parity

import org.jetbrains.exposed.v1.jdbc.Database

/**
 * A [Database] for the areas that run no SQL yet: the executors and the tests give it to a facade, which never
 * connects. Exposed opens a connection only in a transaction; asking this one for a connection is an error. An area that
 * runs SQL takes a real database from TestDatabase instead (the schema area does); this stays for the areas that run
 * none.
 */
val unconnectedDatabase: Database by lazy {
    Database.connect(getNewConnection = { error("no database is connected: this area runs no SQL") })
}
