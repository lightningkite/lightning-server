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
                val withoutScheme = it.url.substringAfter("://")
                val auth = withoutScheme.substringBefore('@', "")
                val user = auth.substringBefore(':').takeUnless { it.isEmpty() }
                val password = auth.substringAfter(':').takeUnless { it.isEmpty() }
                val destination = withoutScheme.substringAfter('@')
                println("Connection info:")
                println("url: jdbc:postgresql://$destination")
                println("user: $user")
                println("password: $password")
                if(user != null && password != null)
                    PostgresDatabase(Database.connect("jdbc:postgresql://$destination", "org.postgresql.Driver", user, password))
                else
                    PostgresDatabase(Database.connect("jdbc:postgresql://$destination", "org.postgresql.Driver"))
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