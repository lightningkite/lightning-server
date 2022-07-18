package com.lightningkite.lightningdb

import kotlinx.serialization.KSerializer
import kotlinx.serialization.StringFormat
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import java.io.Closeable
import java.io.File

class InMemoryUnsafePersistentFieldCollection<Model : Any>(
    val encoding: StringFormat,
    val serializer: KSerializer<Model>,
    val file: File
) : InMemoryFieldCollection<Model>(), Closeable {
    init {
        data.addAll(encoding.decodeFromString(ListSerializer(serializer), file.readText()))
        val shutdownHook = Thread {
            this.close()
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    override fun close() {
        file.writeText(encoding.encodeToString(data))
    }
}

