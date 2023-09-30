package com.lightningkite.lightningserver.files

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.typed.typed
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import com.lightningkite.now
import kotlin.time.Duration
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days

class UploadEarlyEndpoint(
    path: ServerPath,
    val files: () -> FileSystem,
    val database: () -> Database,
    val signer: () -> SecureHasher = secretBasis.hasher("upload-early"),
    val filePath: String = ExternalServerFileSerializer.uploadPath,
    val expiration: Duration = 1.days
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
                expires = now().plus(expiration),
                file = ServerFile(newFile.url)
            )
            database().collection<UploadForNextRequest>().insertOne(newItem)
            UploadInformation(
                uploadUrl = newFile.uploadUrl(expiration),
                futureCallToken = newFile.url.plus("?useUntil=${now().plus(expiration).toEpochMilliseconds()}").let {
                    it + "&token=" + signer().sign(it)
                }
            )
        }
    )
    val cleanupSchedule = schedule("cleanupUploads", 1.days) {
        database().collection<UploadForNextRequest>().deleteMany(condition { it.expires lt now() }).forEach {
            try {
                it.file.fileObject.delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun validateFile(url: String, params: Map<String, String>): Boolean {
        val token = params["token"] ?: return false
        val exp = params["useUntil"]?.toLongOrNull() ?: return false
        if(System.currentTimeMillis() > exp) return false
        val file = ServerFile(url)
        if(!signer().verify(url.substringBefore('?') + "?useUntil=$exp", token)) return false
        GlobalScope.launch {
            database().collection<UploadForNextRequest>()
                .deleteMany(condition { it.file eq ServerFile(url) })
        }
        return true
    }

}