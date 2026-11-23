@file:MustUseReturnValues

package com.lightningkite.lightningserver.media

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.*
import com.lightningkite.services.database.optics.*

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
    makePath: (Optic<T, T>) -> Optic<T, ServerFileWithMetadata?>,
): Table<T> {
    val path = makePath(IdentityLens(serializer))
    return this
        .interceptCreate {
            path.set(
                it,
                path.get(it)?.process(options.toList())
            )
        }
        .interceptChange {
            val new = it.valueSetForOptic(path) ?: return@interceptChange it

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
    makePath: (Optic<T, T>) -> Optic<T, ServerFileWithMetadata>,
): Table<T> =
    interceptImagesForProcessing(
        *options,
        makePath = makePath as (Optic<T, T>) -> Optic<T, ServerFileWithMetadata?>
    )
