package com.lightningkite.lightningdb

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.StringFormat
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import java.io.Closeable
import java.io.File

@OptIn(DelicateCoroutinesApi::class)
class InMemoryUnsafePersistentFieldCollection<Model : Any>(
    val encoding: StringFormat,
    val serializer: KSerializer<Model>,
    val file: File
) : InMemoryFieldCollection<Model>(), Closeable {
    init {
        var closing = false
        data.addAll(encoding.decodeFromString(ListSerializer(serializer), file.takeIf { it.exists() }?.readText() ?: "[]"))
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
        temp.writeText(encoding.encodeToString(ListSerializer(serializer), data))
        temp.renameTo(file)
        println("Saved $file")
    }
}

