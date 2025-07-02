package com.lightningkite.lightningserver.media

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.db.ModelInfo
import com.lightningkite.lightningserver.files.fileObject
import com.lightningkite.lightningserver.files.nameWithoutExtension
import com.lightningkite.lightningserver.files.serverFile
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.http.download
import com.lightningkite.lightningserver.tasks.task
import com.lightningkite.serialization.DataClassPath
import com.lightningkite.serialization.DataClassPathSelf
import com.sksamuel.scrimage.ImmutableImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

suspend fun ServerFileWithMetadata.process(options: Collection<MediaPreviewOptions>): ServerFileWithMetadata = withContext(Dispatchers.IO) {
    if(options.isEmpty()) return@withContext this@process
    if(previews.isNotEmpty()) return@withContext this@process
    val originalFile = original
    val originalFileObject = originalFile.fileObject
    var out = this@process
    val toClean = ArrayList<File>()
    try {
        // Download the original file
        val tempFile = File.createTempFile("original", originalFile.location.substringAfterLast('.'))
        toClean += tempFile
        val content = originalFileObject.get() ?: return@withContext out
        content.download(tempFile)

        out = out.copy(
            size = content.length,
            mimeType = content.type.toString(),
        )

        if(content.type.type != "image") return@withContext out

        val basis = ImmutableImage.loader().fromFile(tempFile)

        out = out.copy(
            width = basis.width,
            height = basis.height
        )

        for(option in options) {
            val result = basis.apply(option, content.type) ?: continue
            val fileObject = originalFileObject.parent!!.resolve(originalFileObject.nameWithoutExtension + "-${option}." + result.mimeType.extension)
            fileObject.put(HttpContent.file(result.file, result.mimeType))
            out = out.copy(previews = out.previews + ServerFileWithMetadataPreview(
                file = fileObject.serverFile,
                mimeType = result.mimeType.toString(),
                size = result.size,
                width = result.width,
                height = result.height,
            ))
        }

        out
    } finally {
        // Clean up temporary files
        toClean.forEach { it.delete() }
    }
}

fun <USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> processImagesInBackground(
    info: ModelInfo<USER, T, ID>,
    vararg options: MediaPreviewOptions,
    pathMaker: (DataClassPathSelf<T>) -> DataClassPath<T, ServerFileWithMetadata?>
): (FieldCollection<T>) -> FieldCollection<T> {
    val path = pathMaker(DataClassPathSelf(info.serialization.serializer))
    val name = "${info.collectionName}_${path.properties.joinToString("_") { it.name }}_imageProcessor"
    val task = task(name, info.serialization.serializer) { newItem ->
        val new = newItem.let { path.get(it) } ?: return@task
        withContext(Dispatchers.IO) {
            val processed = new.process(options.toList())
            // Update the model in the database using a simple approach
            info.collection().updateOneById(newItem._id, path.mapModification(Modification.Assign(processed)))
        }
    }
    return {
        it.postRawChanges {
            it.forEach { (oldItem, newItem) ->
                val new = newItem?.let { path.get(it) }
                if (new != null && oldItem?.let { path.get(it) } != new) {
                    task(newItem)
                }
            }
        }
    }
}

fun <T: Any> FieldCollection<T>.interceptImagesForProcessing(
    vararg options: MediaPreviewOptions,
    pathMaker: (DataClassPathSelf<T>) -> DataClassPath<T, ServerFileWithMetadata?>
): FieldCollection<T> {
    val path = pathMaker(DataClassPathSelf(serializer))
    return interceptChange {
        val newFile = it.valueSetForDataClassPath(path) ?: return@interceptChange it
        val procesed = newFile.process(options.toList())
        Modification.Chain(listOf(
            it,
            path.mapModification(Modification.Assign(procesed))
        )).simplify()
    }.interceptCreate {
        path.set(it, path.get(it)?.process(options.toList()))
    }
}

fun <T: Any> FieldCollection<T>.interceptImagesForProcessingNotNull(
    vararg options: MediaPreviewOptions,
    pathMaker: (DataClassPathSelf<T>) -> DataClassPath<T, ServerFileWithMetadata>
): FieldCollection<T> = interceptImagesForProcessing(*options, pathMaker = pathMaker as (DataClassPathSelf<T>) -> DataClassPath<T, ServerFileWithMetadata?>)
