package com.lightningkite.lightningserver.db

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.jetbrains.exposed.sql.Database
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KType

class PostgresDatabase(val db: Database) : com.lightningkite.lightningdb.Database {
    companion object {
        init {
            // postgresql://user:password@endpoint/database
            DatabaseSettings.register("postgresql") {
                Regex("""postgresql://([^:]*)([^@]*)@(.+)""").matchEntire(it.url)?.let { match ->
                    val user = match.groupValues[1]
                    val password = match.groupValues[2]
                    if (user.isNotBlank() && password.isNotBlank())
                        PostgresDatabase(
                            Database.connect(
                                "jdbc:postgresql://${match.groupValues[3]}",
                                "org.postgresql.Driver",
                                user,
                                password
                            )
                        )
                    else
                        PostgresDatabase(
                            Database.connect(
                                "jdbc:postgresql://${match.groupValues[3]}",
                                "org.postgresql.Driver"
                            )
                        )
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
                    db,
                    name,
                    PostgresCollection.format.serializersModule.serializer(type) as KSerializer<T>
                )
            }
        } as Lazy<PostgresCollection<T>>).value
}