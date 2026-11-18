package com.lightningkite.lightningserver.guide.samples

// region media-imports
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.files.FileUploadBasis
import com.lightningkite.lightningserver.media.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.Queryable
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.files.ExternalFileSystem
import kotlin.uuid.Uuid
// endregion media-imports

// region media-server
@Queryable
data class GalleryPhoto(
    override val _id: Uuid = Uuid.random(),
    val caption: String,
    val image: ServerFileWithMetadata? = null,
) : HasId<Uuid>

object GalleryServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val files = setting("files", ExternalFileSystem.Settings())

    // Resolving a ServerFile to storage needs the serializer an upload basis installs.
    val uploads = path.path("uploads") include FileUploadBasis(files, database, Runtime.Constant(listOf()))

    val photoInfo = database.modelInfo<HasId<*>?, GalleryPhoto, Uuid>(
        auth = noAuth,
        tableName = "GalleryPhoto",
        signals = { table ->
            table.interceptImagesForProcessing(
                MediaPreviewOptions(sizeInPixels = 200, type = MediaType.Image.JPEG),
                MediaPreviewOptions(sizeInPixels = 1200),
                makePath = { it.image },
            )
        },
        permissions = { ModelPermissions.allowAll() },
    )

    val photos = path.path("photos") include ModelRestEndpoints(photoInfo)

    init {
        registerBasicMediaTypeCoders()
    }
}
// endregion media-server

// region media-background
object BackgroundGalleryServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val files = setting("files", ExternalFileSystem.Settings())
    val uploads = path.path("uploads") include FileUploadBasis(files, database, Runtime.Constant(listOf()))

    val photoInfo = database.modelInfo<HasId<*>?, GalleryPhoto, Uuid>(
        auth = noAuth,
        tableName = "GalleryPhoto",
        permissions = { ModelPermissions.allowAll() },
    )

    // Must be bound to a path, or it logs a warning and never runs.
    val processPhotos = path.path("process-photos") bind processImagesInBackground(
        photoInfo,
        MediaPreviewOptions(sizeInPixels = 200, type = MediaType.Image.JPEG),
        makePath = { it.image },
    )

    val photos = path.path("photos") include ModelRestEndpoints(photoInfo)
}
// endregion media-background

// region media-pick
fun thumbnailFor(photo: ServerFileWithMetadata) = photo
    .previews(setOf(MediaType.Image.JPEG, MediaType.Image.WebP), preferredMinimumWidth = 150)
    .firstOrNull()
    ?.file
    ?: photo.original
// endregion media-pick
