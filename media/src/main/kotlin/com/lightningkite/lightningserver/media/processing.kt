package com.lightningkite.lightningserver.media

import com.lightningkite.lightningserver.data.toKFile
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.files.fileObject
import com.lightningkite.lightningserver.files.nameWithoutExtension
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.*
import com.lightningkite.services.files.serverFile
import com.sksamuel.scrimage.ImmutableImage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.asInputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Processes a server file to generate preview variants according to the specified options.
 *
 * This function downloads the original file, extracts metadata (size, dimensions for images),
 * and generates preview variants for each specified option. Preview files are stored alongside
 * the original file with descriptive suffixes (e.g., "filename-800-jpg.jpg").
 *
 * **Behavior:**
 * - If no options are provided, returns the original metadata unchanged
 * - If previews already exist, returns the metadata unchanged (idempotent)
 * - Only processes image files (checks MIME type)
 * - Skips options that don't require any transformation (optimization)
 * - Executes on IO dispatcher for file operations
 *
 * **File naming:** Generated previews use the pattern: `{original-name}-{options}.{extension}`
 * where `{options}` is the string representation of [MediaPreviewOptions].
 *
 * @param options Collection of preview options to generate
 * @return Updated metadata including all generated preview variants
 * @receiver ServerFileWithMetadata to process
 * @throws NullPointerException if the original file's parent directory is null
 */
context(runtime: ServerRuntime)
public suspend fun ServerFileWithMetadata.process(options: Collection<MediaPreviewOptions>): ServerFileWithMetadata =
    withContext(Dispatchers.IO) {
        if (options.isEmpty()) return@withContext this@process
        if (previews.isNotEmpty()) return@withContext this@process

        val originalFile = original
        val originalFileObject = originalFile.fileObject

        var out = this@process

        val content = originalFileObject.get() ?: return@withContext out

        out = out.copy(
            size = content.data.size,
            mimeType = content.mediaType,
        )

        if (content.mediaType.type != "image") return@withContext out

        val basis = ImmutableImage.loader().fromStream(content.data.source().asInputStream())

        out = out.copy(
            width = basis.width,
            height = basis.height
        )

        for (option in options) {
            val result = basis.apply(option, content.mediaType) ?: continue
            // No NPE risk here; the file object has to be an actual file, not just the root directory.  This is safe.
            val fileObject =
                originalFileObject.parent!!.then(originalFileObject.nameWithoutExtension + "-${option}." + result.mimeType.extension)

            fileObject.put(TypedData.source(result.file.toKFile().source(), result.mimeType))

            out = out.copy(
                previews = out.previews + ServerFileWithMetadataPreview(
                    file = fileObject.serverFile,
                    mimeType = result.mimeType,
                    size = result.size,
                    width = result.width,
                    height = result.height,
                )
            )
        }

        out
    }

/**
 * Creates a background task that automatically processes images when database records change.
 *
 * This function sets up a listener on the specified model that triggers image processing whenever
 * the targeted [ServerFileWithMetadata] field is modified. The task must be registered with the
 * server (bound to a path) to function properly.
 *
 * **How it works:**
 * 1. Registers a change listener on the model's database table
 * 2. When a record is created or the specified field changes, triggers the task
 * 3. The task processes the image and updates the database with preview metadata
 *
 * **Important:** The returned task must be bound to a server path (e.g., `val myTask = path.task bind processImagesInBackground(...)`)
 * or it will log a warning and not execute.
 *
 * @param USER The user type for authentication (use `Nothing?` for unauthenticated)
 * @param T The model type containing the file metadata field
 * @param ID The ID type of the model
 * @param info Model metadata including serializer and table access
 * @param options Preview generation options to apply
 * @param timeout Maximum time allowed for processing a single image (default: 5 minutes)
 * @param makePath Function that maps from the model root to the ServerFileWithMetadata field
 * @return A Task that processes images in the background
 *
 * @see interceptImagesForProcessing for synchronous processing during create/update operations
 */
public fun <USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> processImagesInBackground(
    info: ModelInfo<USER, T, ID>,
    vararg options: MediaPreviewOptions,
    timeout: Duration = 5.minutes,
    makePath: (DataClassPath<T, T>) -> DataClassPath<T, ServerFileWithMetadata?>,
): Task<T> {
    val path = makePath(DataClassPathSelf(info.serializer))

    val task = Task(info.serializer, timeout) { model ->
        val new = path.get(model) ?: return@Task
        withContext(Dispatchers.IO) {
            info.table().updateOneById(
                model._id,
                path.mapModification(
                    Modification.Assign(
                        new.process(options.toList())
                    )
                )
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

/**
 * Intercepts database operations to synchronously process images before they are stored.
 *
 * This function wraps a database table to automatically process images during create and update
 * operations. Unlike [processImagesInBackground], this processes images **synchronously** before
 * the database write completes, ensuring previews are always generated.
 *
 * **Use cases:**
 * - When previews must be available immediately after creation
 * - When you need guaranteed preview generation
 * - For critical images where background processing is not acceptable
 *
 * **Trade-offs:**
 * - **Synchronous:** Blocks the API response until processing completes
 * - **Guaranteed:** Previews are always generated before the response returns
 * - **Performance:** May slow down create/update operations for large images
 *
 * @param T The model type
 * @param options Preview generation options to apply
 * @param makePath Function that maps from the model root to the ServerFileWithMetadata field (nullable)
 * @return The table with processing interceptors applied
 *
 * @see processImagesInBackground for asynchronous processing
 * @see interceptImagesForProcessingNotNull for non-nullable field variant
 */
context(_: ServerRuntime)
public fun <T : Any> Table<T>.interceptImagesForProcessing(
    vararg options: MediaPreviewOptions,
    makePath: (DataClassPath<T, T>) -> DataClassPath<T, ServerFileWithMetadata?>,
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

            Modification.Chain(
                listOf(
                    it,
                    path.mapModification(
                        Modification.Assign(
                            new.process(options.toList())
                        )
                    )
                )
            )
        }
}

/**
 * Intercepts database operations to synchronously process images before they are stored.
 *
 * This is a convenience variant of [interceptImagesForProcessing] for non-nullable
 * [ServerFileWithMetadata] fields. The behavior is identical, but the type signature
 * ensures the field is always present.
 *
 * @param T The model type
 * @param options Preview generation options to apply
 * @param makePath Function that maps from the model root to the ServerFileWithMetadata field (non-null)
 * @return The table with processing interceptors applied
 *
 * @see interceptImagesForProcessing for the nullable variant
 */
@Suppress("UNCHECKED_CAST")
context(_: ServerRuntime)
public fun <T : Any> Table<T>.interceptImagesForProcessingNotNull(
    vararg options: MediaPreviewOptions,
    makePath: (DataClassPath<T, T>) -> DataClassPath<T, ServerFileWithMetadata>,
): Table<T> =
    interceptImagesForProcessing(
        *options,
        makePath = makePath as (DataClassPath<T, T>) -> DataClassPath<T, ServerFileWithMetadata?>
    )

/*
 * TODO: API Recommendations
 *
 * 1. Consider adding a hybrid approach: fast synchronous processing for small images, background
 *    queue for large ones, with a configurable size threshold.
 *
 * 2. The interceptImagesForProcessing methods process on every update, even if the file field
 *    didn't change. Consider optimizing to only process when the field actually changes.
 *
 * 3. Consider adding error handling and retry logic for failed image processing operations.
 *
 * 4. The naming distinction between processImagesInBackground and interceptImagesForProcessing
 *    could be clearer. Consider renaming to emphasize sync vs async (e.g., processImagesAsync
 *    and processImagesSync).
 *
 * 5. Consider adding telemetry/metrics for image processing operations (processing time, file sizes,
 *    success/failure rates) to help users optimize their preview configurations.
 *
 * 6. Consider allowing users to specify which operations to intercept (create only, update only,
 *    or both) for more granular control.
 */