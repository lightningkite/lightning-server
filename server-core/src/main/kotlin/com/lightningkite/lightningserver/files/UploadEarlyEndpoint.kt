package com.lightningkite.lightningserver.files

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.JwtSigner
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.exceptions.UnauthorizedException
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.typed.typed
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.time.Duration
import java.time.Instant
import java.util.*

class UploadEarlyEndpoint(
    path: ServerPath,
    val files: () -> FileSystem,
    val database: () -> Database,
    val signer: () -> JwtSigner,
    val filePath:String = ExternalServerFileSerializer.uploadPath
) : ServerPathGroup(path) {

    companion object {
        var default: UploadEarlyEndpoint? = null
    }

    init {
        prepareModels()
        ExternalServerFileSerializer.fileValidators += this::validateFile
        ExternalServerFileSerializer.fileSystem = files
        default = this
    }

    val endpoint = get.typed(
        summary = "Upload File for Request",
        description = "Upload a file to make a request later.  Times out in around 10 minutes.",
        errorCases = listOf(),
        implementation = { user: Unit, nothing: Unit ->
            val newFile = files().root.resolve(filePath).resolveRandom("file", "file")
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
    val cleanupSchedule = schedule("cleanupUploads", Duration.ofDays(1)) {
        database().collection<UploadForNextRequest>().deleteMany(condition { it.expires lt Instant.now() }).forEach {
            try {
                it.file.fileObject.delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun validateFile(url: String, params: Map<String, String>): Boolean {
        return params["token"]?.let { token ->
            try {
                val tokenUrl = signer().verify(token)
                if (url == tokenUrl) {
                    GlobalScope.launch {
                        database().collection<UploadForNextRequest>()
                            .deleteMany(condition { it.file eq ServerFile(url) })
                    }
                    true
                } else false

            } catch (e: UnauthorizedException) {
                false
            }
        } ?: false
    }

}