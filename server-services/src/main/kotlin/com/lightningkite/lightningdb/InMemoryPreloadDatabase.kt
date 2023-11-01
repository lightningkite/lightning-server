package com.lightningkite.lightningdb

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import java.io.File

/**
 * A Database implementation whose data manipulation is entirely in the application Heap, but it will attempt to store the data into a Folder on the system before shutdown.
 * On startup it will load in the Folder contents and populate the database.
 * It uses InMemoryUnsafePersistentFieldCollection in its implementation. This is NOT meant for long term storage.
 * It is NOT guaranteed that it will store the data before the application is shut down. There is a HIGH chance that the changes will not persist between runs.
 * This is useful in places that persistent data is not important and speed is desired.
 *
 * @param folder The File references a directory where you wish the data to be stored.
 */
class InMemoryPreloadDatabase(val folder: File) : Database {
    init {
        folder.mkdirs()
    }

    val collections = HashMap<Pair<KSerializer<*>, String>, FieldCollection<*>>()

    override fun <T : Any> collection(module: SerializersModule, serializer: KSerializer<T>, name: String): FieldCollection<T> = synchronized(collections) {
        collections.getOrPut(serializer to name) {
            val fileName = name.filter { it.isLetterOrDigit() }
            val oldStyle = folder.resolve(fileName)
            val storage = folder.resolve("$fileName.json")
            if (oldStyle.exists() && !storage.exists())
                oldStyle.copyTo(storage, overwrite = true)
            InMemoryPreloadFieldCollection(
                Json { this.serializersModule = module },
                serializer,
                storage
            )
        } as FieldCollection<T>
    }
}