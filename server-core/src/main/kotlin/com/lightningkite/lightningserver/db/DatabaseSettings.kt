package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.Pluggable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KType

/**
 * Settings that define what database to use and how to connect to it.
 *
 * @param url Defines the type and connection to the database. Examples are ram, ram-preload, ram-unsafe-persist
 * @param databaseName The name of the database to connect to.
 */
@Serializable
data class DatabaseSettings(
    val url: String = "ram-unsafe-persist://${File("./local/database").absolutePath}",
) : () -> Database {

    companion object : Pluggable<DatabaseSettings, Database>() {
        init {
            register("ram") { InMemoryDatabase() }
            register("ram-preload") {
                InMemoryDatabase(
                    Serialization.Internal.json.parseToJsonElement(
                        File(it.url.substringAfter("://"))
                            .readText()
                    ) as? JsonObject
                )
            }
            register("ram-unsafe-persist") { InMemoryUnsafePersistenceDatabase(File(it.url.substringAfter("://"))) }
            register("delay") {
                val x = it.url.substringAfter("://")
                val delay = x.substringBefore("/").toLong()
                val wraps = x.substringAfter("/")
                parse(wraps.substringBefore("://"), DatabaseSettings(wraps)).delayed(delay)
            }
        }
    }

    @Transient val invoked = AtomicBoolean(false)
    override fun invoke(): Database {
        if(!invoked.compareAndSet(false, true)) throw Error()
        return parse(url.substringBefore("://"), this)
    }
}

/**
 * A Database implementation that exists entirely in the applications Heap. There are no external connections.
 * It uses InMemoryFieldCollections in its implementation. This is NOT meant for persistent or long term storage.
 * This database will be completely erased everytime the application is stopped.
 * This is useful in places that persistent data is not needed and speed is desired such as Unit Tests.
 *
 * @param premadeData A JsonObject that contains data you wish to populate the database with on creation.
 */
class InMemoryDatabase(val premadeData: JsonObject? = null) : Database {
    val collections = HashMap<Pair<KSerializer<*>, String>, FieldCollection<*>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> collection(serializer: KSerializer<T>, name: String): FieldCollection<T> = collections.getOrPut(serializer to name) {
        val made = InMemoryFieldCollection(serializer = serializer)
        premadeData?.get(name)?.let {
            val data = Serialization.Internal.json.decodeFromJsonElement(
                ListSerializer(serializer),
                it
            )
            made.data.addAll(data)
        }
        made
    } as FieldCollection<T>

}


/**
 * A Database implementation whose data manipulation is entirely in the application Heap, but it will attempt to store the data into a Folder on the system before shutdown.
 * On startup it will load in the Folder contents and populate the database.
 * It uses InMemoryUnsafePersistentFieldCollection in its implementation. This is NOT meant for long term storage.
 * It is NOT guaranteed that it will store the data before the application is shut down. There is a HIGH chance that the changes will not persist between runs.
 * This is useful in places that persistent data is not important and speed is desired.
 *
 * @param folder The File references a directory where you wish the data to be stored.
 */
class InMemoryUnsafePersistenceDatabase(val folder: File) : Database {
    init {
        folder.mkdirs()
    }

    val collections = HashMap<Pair<KSerializer<*>, String>, FieldCollection<*>>()

    override fun <T : Any> collection(serializer: KSerializer<T>, name: String): FieldCollection<T> = synchronized(collections) {
        @Suppress("UNCHECKED_CAST")
        collections.getOrPut(serializer to name) {
            val fileName = name.filter { it.isLetterOrDigit() }
            val oldStyle = folder.resolve(fileName)
            val storage = folder.resolve("$fileName.json")
            if (oldStyle.exists() && !storage.exists())
                oldStyle.copyTo(storage, overwrite = true)
            InMemoryUnsafePersistentFieldCollection(
                Serialization.Internal.json,
                serializer,
                storage
            )
        } as FieldCollection<T>
    }
}