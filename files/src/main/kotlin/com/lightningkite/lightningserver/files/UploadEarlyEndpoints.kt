package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.data.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.builder.bind
import com.lightningkite.lightningserver.deprecations.*
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.terraform.*
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.deprecations.*
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.services.database.*
import com.lightningkite.services.files.*
import dev.whyoleg.cryptography.algorithms.HMAC
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

public class UploadEarlyEndpoint(
    public val files: Runtime<PublicFileSystem>,
    public val database: Runtime<Database>,
    public val fileScanner: Runtime<List<FileScanner>>,
    public val jailFilePath: String = "upload-jail",
    public val filePath: String = "uploaded",
    public val expiration: Duration = 1.days,
    public val authOptions: AuthRequirement<*> = noAuth,
) : ServerBuilder() {

    public val serializer: Runtime<ExternalServerFileSerializer> = Runtime.Cached {
        val rt = contextOf<ServerRuntime>()
        ExternalServerFileSerializer(
            clock = rt.clock,
            scanners = fileScanner(),
            fileSystems = listOf(files()),
            onUse = { fileObject ->
                runBlocking {
                    database().collection<UploadForNextRequest>()
                        .deleteManyIgnoringOld(condition { it.file eq ServerFile(fileObject.url) })
                }
            },
            key = secretBasis().HMAC_Blocking("upload-files")
        )
    }

    public val endpoint: ApiHttpHandler<PathSpec0, HasId<*>?, Unit, UploadInformation> =
        path.get bind ApiHttpHandler(
        auth = authOptions,
        summary = "Upload File for Request",
        description = "Upload a file to make a request later.  Times out in around 10 minutes.",
        errorCases = listOf(),
        implementation = { _: Unit ->
            val id = Uuid.random()
            if (fileScanner().isEmpty()) {
                val newFile = files().root.resolve(filePath).resolve("$id.file")
                val newItem = UploadForNextRequest(
                    expires = now().plus(expiration),
                    file = ServerFile(newFile.url)
                )
                database().collection<UploadForNextRequest>().insertOne(newItem)
                UploadInformation(
                    uploadUrl = newFile.uploadUrl(expiration),
                    futureCallToken = serializer().certifyForUse(newFile, expiration)
                )
            } else {
                val newFile = files().root.resolve(jailFilePath).resolve("$id.file")
                val newItem = UploadForNextRequest(
                    expires = now().plus(expiration),
                    file = ServerFile(newFile.url)
                )
                database().collection<UploadForNextRequest>().insertOne(newItem)
                UploadInformation(
                    uploadUrl = newFile.uploadUrl(expiration),
                    futureCallToken = serializer().certifyForUse(newFile, expiration)
                )
            }
        }
    )

    public val verify: ApiHttpHandler<PathSpec0, HasId<*>?, String, String> =
        path.path("verify").post bind ApiHttpHandler(
        auth = authOptions,
        summary = "Verify uploaded file",
        description = "Checks out a file and moves it out of jail if it's safe.  Makes for significantly faster subsequent requests.",
        errorCases = listOf(),
        implementation = { url: String ->
            serializer().scan(url, expiration)
        }
    )

    public val cleanupSchedule: ScheduledTask = path.path("cleanupUploads") bind ScheduledTask(frequency = 1.days) {
        database().collection<UploadForNextRequest>().deleteMany(condition { it.expires lt now() }).forEach {
            try {
                files().parseInternalUrl(it.file.location)!!.delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}