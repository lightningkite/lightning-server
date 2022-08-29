@file:UseContextualSerialization(UUID::class, ServerFile::class, Instant::class)
package com.lightningkite.lightningserver.files

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.JwtSigner
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.typed.typed
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.time.Duration
import java.time.Instant
import java.util.*

@DatabaseModel
@Serializable
data class UploadForNextRequest(
    override val _id: UUID = UUID.randomUUID(),
    val file: ServerFile,
    val expires: Instant = Instant.now().plus(Duration.ofMinutes(15))
): HasId<UUID>

@Serializable
data class UploadInformation(
    val uploadUrl: String,
    val futureCallToken: String
)

class UploadEarlyEndpoint(val files: ()->FileSystem, val database: ()->Database, val signer: ()->JwtSigner) {
    val endpoint = ServerPath.root.path("upload").get.typed(
        summary = "Upload File for Request",
        description = "Upload a file to make a request later.  Times out in around 10 minutes.",
        errorCases = listOf(),
        implementation = { user: Unit, nothing: Unit ->
            val newFile = files().root.resolveRandom("upload", "")
            val newItem = UploadForNextRequest(
                file = ServerFile(newFile.url)
            )
            database().collection<UploadForNextRequest>().insertOne(newItem)
            UploadInformation(
                uploadUrl = newFile.uploadUrl(Duration.ofMinutes(15)),
                futureCallToken = newFile.url + "?token=" + signer().token(newFile.url, Duration.ofMinutes(15))
            )
        }
    )
    val cleanupSchedule = schedule("cleanupUploads", Duration.ofMinutes(15)) {
        database().collection<UploadForNextRequest>().deleteMany(condition { it.expires lt Instant.now() }).forEach {
            try { it.file.fileObject.delete() } catch(e: Exception) { e.printStackTrace() }
        }
    }
}