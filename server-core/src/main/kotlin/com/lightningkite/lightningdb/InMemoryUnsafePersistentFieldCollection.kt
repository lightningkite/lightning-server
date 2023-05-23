package com.lightningkite.lightningdb

import kotlinx.serialization.KSerializer
import kotlinx.serialization.StringFormat
import kotlinx.serialization.builtins.ListSerializer
import java.io.Closeable
import java.io.File
import java.util.*

/**
 * An InMemoryFieldCollection with the added feature of loading data from a file at creation
 * and writing the collection data into a file when closing.
 */
class InMemoryUnsafePersistentFieldCollection<Model : Any>(
    val encoding: StringFormat,
    serializer: KSerializer<Model>,
    val file: File
) : InMemoryFieldCollection<Model>(
    data = Collections.synchronizedList(ArrayList()),
    serializer = serializer
),
    Closeable {
    init {
        var closing = false
        data.addAll(
            encoding.decodeFromString(
                ListSerializer(serializer),
                file.takeIf { it.exists() }?.readText() ?: "[]"
            )
        )
        val shutdownHook = Thread {
            closing = true
            this.close()
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        this.signals.add {
            close()
        }
    }

    override fun close() {
        val temp = file.parentFile!!.resolve(file.name + ".saving")
        temp.writeText(encoding.encodeToString(ListSerializer(serializer), data.toList()))
        temp.renameTo(file)
        logger.debug("Saved $file")
    }
}

