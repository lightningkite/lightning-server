package com.lightningkite.lightningserver.db

import com.lightningkite.lightningserver.core.Disconnectable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KType

class PostgresDatabase(private val makeDb: () -> Database) : com.lightningkite.lightningdb.Database, Disconnectable {
    var db = lazy(makeDb)

    override suspend fun disconnect() {
        if(db.isInitialized()) TransactionManager.closeAndUnregister(db.value)
        db = lazy(makeDb)
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

    private val collections = ConcurrentHashMap<String, Lazy<PostgresCollection<*>>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> collection(type: KType, name: String): PostgresCollection<T> =
        (collections.getOrPut(name) {
            lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                PostgresCollection(
                    db.value,
                    name,
                    PostgresCollection.format.serializersModule.serializer(type) as KSerializer<T>
                )
            }
        } as Lazy<PostgresCollection<T>>).value

}