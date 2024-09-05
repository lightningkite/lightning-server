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
import com.lightningkite.UUID

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
), Closeable{

    private val scope = CoroutineScope(Dispatchers.IO)

    @OptIn(ObsoleteCoroutinesApi::class)
    val saveScope = scope.actor<Unit>(start = CoroutineStart.LAZY) {
        handleCollectionDump()
    }

    init {
        data.addAll(
            encoding.decodeFromString(
                ListSerializer(serializer),
                file.takeIf { it.exists() }?.readText() ?: "[]"
            )
        )
        val shutdownHook = Thread {
            handleCollectionDump()
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    override fun close() {
        scope.launch {
            saveScope.send(Unit)
        }
    }

    fun handleCollectionDump() {
        val temp = file.parentFile!!.resolve(file.name + ".saving")
        temp.writeText(encoding.encodeToString(ListSerializer(serializer), data.toList()))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        logger.debug("Saved $file")
    }
}

