package com.lightningkite.lightningserver.db

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.jetbrains.exposed.sql.Database
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KType

class PostgresDatabase(val db: Database) : com.lightningkite.lightningdb.Database {
    companion object {
        init {
            DatabaseSettings.register("postgres") {
                PostgresDatabase(Database.connect(it.url))
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