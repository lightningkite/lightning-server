package com.lightningkite.lightningserver.db

import com.lightningkite.lightningserver.core.Disconnectable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KType

// This being a lambda to return a Database rather than the database itself is
// to support the Disconnectable interface.
class PostgresDatabase(private val makeDb: () -> Database) : com.lightningkite.lightningdb.Database, Disconnectable {
    private var _db = lazy(makeDb)

    val db: Database get() = _db.value

    override suspend fun disconnect() {
        if(_db.isInitialized()) TransactionManager.closeAndUnregister(_db.value)
        _db = lazy(makeDb)
    }

    override suspend fun connect() {
        // KEEP THIS AROUND.
        // This initializes the database call at startup.
        healthCheck()
    }

    companion object {
        init {
            // postgresql://user:password@endpoint/database
            DatabaseSettings.register("postgresql") {
                Regex("""postgresql://(?<user>[^:]*)(?<password>[^@]*)@(?<destination>.+)""").matchEntire(it.url)?.let { match ->
                    val user = match.groups["user"]!!.value
                    val password = match.groups["password"]!!.value
                    val destination = match.groups["destination"]!!.value
                    if (user.isNotBlank() && password.isNotBlank())
                        PostgresDatabase {
                            Database.connect(
                                "jdbc:postgresql://$destination",
                                "org.postgresql.Driver",
                                user,
                                password
                            )
                        }
                    else
                        PostgresDatabase {
                            Database.connect(
                                "jdbc:postgresql://$destination",
                                "org.postgresql.Driver"
                            )
                        }
                }
                    ?: throw IllegalStateException("Invalid Postgres Url. The URL should match the pattern: postgresql://[user]:[password]@[destination]")
            }
        }
    }

    private val collections = ConcurrentHashMap<Pair<KSerializer<*>, String>, Lazy<PostgresCollection<*>>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> collection(serializer: KSerializer<T>, name: String): PostgresCollection<T> =
        (collections.getOrPut(serializer to name) {
            lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                PostgresCollection(
                    db,
                    name,
                    serializer
                )
            }
        } as Lazy<PostgresCollection<T>>).value

}