package com.lightningkite.lightningserver.media

import com.lightningkite.lightningserver.data.toKFile
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.files.fileObject
import com.lightningkite.lightningserver.files.nameWithoutExtension
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.invoke
import com.lightningkite.lightningserver.runtime.locationOrNull
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.DataClassPath
import com.lightningkite.services.database.DataClassPathSelf
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.and
import com.lightningkite.services.database.interceptChange
import com.lightningkite.services.database.interceptCreate
import com.lightningkite.services.database.map
import com.lightningkite.services.database.updateOneById
import com.lightningkite.services.database.valueSetForDataClassPath
import com.lightningkite.services.files.serverFile
import com.sksamuel.scrimage.ImmutableImage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

context(runtime: ServerRuntime)
public suspend fun ServerFileWithMetadata.process(options: Collection<MediaPreviewOptions>): ServerFileWithMetadata = withContext(Dispatchers.IO) {
    if (options.isEmpty()) return@withContext this@process
    if (previews.isNotEmpty()) return@withContext this@process

    val originalFile = original
    val originalFileObject = originalFile.fileObject

    var out = this@process
    val toClean = ArrayList<File>()

    try {
        // Download the original file
        val tempFile = File.createTempFile("original", originalFile.location.substringAfterLast('.'))
        toClean += tempFile

        val content = originalFileObject.get() ?: return@withContext out

        content.write(tempFile.toKFile().sink())

        out = out.copy(
            size = content.data.size,
            mediaType = content.mediaType,
        )

        if (content.mediaType.type != "image") return@withContext out

        val basis = ImmutableImage.loader().fromFile(tempFile)

        out = out.copy(
            width = basis.width,
            height = basis.height
        )

        for (option in options) {
            val result = basis.apply(option, content.mediaType) ?: continue
            val fileObject = originalFileObject.parent!!.then(originalFileObject.nameWithoutExtension + "-${option}." + result.mimeType.extension)

            fileObject.put(TypedData.source(result.file.toKFile().source(), result.mimeType))

            out = out.copy(
                previews = out.previews + ServerFileWithMetadataPreview(
                    file = fileObject.serverFile,
                    mediaType = result.mimeType,
                    size = result.size,
                    width = result.width,
                    height = result.height,
                )
            )
        }

        out
    } finally {
        // Clean up temporary files
        toClean.forEach { it.delete() }
    }
}

public fun <USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> processImagesInBackground(
    info: ModelInfo<USER, T, ID>,
    vararg options: MediaPreviewOptions,
    timeout: Duration = 5.minutes,
    makePath: (DataClassPath<T, T>) -> DataClassPath<T, ServerFileWithMetadata?>
): Task<T> {
    val path = makePath(DataClassPathSelf(info.serializer))

    val task = Task(info.serializer, timeout) { model ->
        val new = path.get(model) ?: return@Task
        withContext(Dispatchers.IO) {
            info.table().updateOneById(
                model._id,
                path.mapModification(Modification.Assign(
                    new.process(options.toList())
                ))
            )
        }
    }

    info.registerChangeListener { changes ->
        if (task.locationOrNull == null) {
            KotlinLogging.logger("com.lightningkite.lightningserver.media.processImagesInBackground").warn {
                "processImagesInBackground is unregistered and cannot be executed. Please bind the task to a path to process images."
            }
            return@registerChangeListener
        }
        changes.changes.forEach {
            val current = it.new
            val (old, new) = it.map(path::get)
            if (current != null && old != new) task(current)
        }
    }

    return task
}

context(_: ServerRuntime)
public fun <T : Any> Table<T>.interceptImagesForProcessing(
    vararg options: MediaPreviewOptions,
    makePath: (DataClassPath<T, T>) -> DataClassPath<T, ServerFileWithMetadata?>
): Table<T> {
    val path = makePath(DataClassPathSelf(serializer))
    return this
        .interceptCreate {
            path.set(
                it,
                path.get(it)?.process(options.toList())
            )
        }
        .interceptChange {
            val new = it.valueSetForDataClassPath(path) ?: return@interceptChange it

            Modification.Chain(listOf(
                it,
                path.mapModification(Modification.Assign(
                    new.process(options.toList())
                ))
            ))
        }
}

@Suppress("UNCHECKED_CAST")
context(_: ServerRuntime)
public fun <T : Any> Table<T>.interceptImagesForProcessingNotNull(
    vararg options: MediaPreviewOptions,
    makePath: (DataClassPath<T, T>) -> DataClassPath<T, ServerFileWithMetadata>
): Table<T> =
    interceptImagesForProcessing(*options, makePath = makePath as (DataClassPath<T, T>) -> DataClassPath<T, ServerFileWithMetadata?>)