package com.lightningkite.lightningdb

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.actor
import kotlinx.serialization.KSerializer
import kotlinx.serialization.StringFormat
import kotlinx.serialization.builtins.ListSerializer
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.*

/**
 * An InMemoryFieldCollection with the added feature of loading data from a file at creation
 * and writing the collection data into a file when closing.
 */
class InMemoryPreloadFieldCollection<Model : Any>(
    val encoding: StringFormat,
    serializer: KSerializer<Model>,
    val file: File
) : InMemoryFieldCollection<Model>(
    data = Collections.synchronizedList(ArrayList()),
    serializer = serializer
) {
    init {
        data.addAll(
            encoding.decodeFromString(
                ListSerializer(serializer),
                file.takeIf { it.exists() }?.readText() ?: "[]"
            )
        )
    }
}

