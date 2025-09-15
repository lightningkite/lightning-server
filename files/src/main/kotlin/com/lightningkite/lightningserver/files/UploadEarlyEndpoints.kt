package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.builder.bind
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.services.database.*
import com.lightningkite.services.files.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.modules.SerializersModule
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
            jail = files().root.then(jailFilePath),
            ready = files().root.then(filePath),
            fileSystems = listOf(files()),
            onUse = { fileObject ->
                runBlocking {
                    database().table<UploadForNextRequest>()
                        .deleteManyIgnoringOld(condition { it.file eq ServerFile(fileObject.url) })
                }
            },
            key = secretBasis().HMAC_Blocking("upload-files")
        )
    }

    override val externalSerialization: Runtime<SerializersModule> = Runtime.Cached {
        SerializersModule {
            contextual(ServerFile::class, serializer())
        }
    }

    public val endpoint: ApiHttpHandler<PathSpec0, HasId<*>?, Unit, UploadInformation> =
        path.get bind ApiHttpHandler(
        auth = authOptions,
        summary = "Upload File for Request",
        description = "Upload a file to make a request later.  Times out in around 10 minutes.",
        errorCases = listOf(),
        implementation = { _: Unit ->
            val id = Uuid.random()
            val key = "$id.file"
            if (fileScanner().isEmpty()) {
                val newFile = serializer().ready.then(key)
                val newItem = UploadForNextRequest(
                    expires = now().plus(expiration),
                    file = ServerFile(newFile.url)
                )
                database().table<UploadForNextRequest>().insertOne(newItem)
                UploadInformation(
                    uploadUrl = newFile.uploadUrl(expiration),
                    futureCallToken = serializer().certifyAlreadyScannedForUse(key, expiration)
                )
            } else {
                val newFile = serializer().jail.then(key)
                val newItem = UploadForNextRequest(
                    expires = now().plus(expiration),
                    file = ServerFile(newFile.url)
                )
                database().table<UploadForNextRequest>().insertOne(newItem)
                UploadInformation(
                    uploadUrl = newFile.uploadUrl(expiration),
                    futureCallToken = serializer().certifyForUse(key, expiration)
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
            val url = serializer().scan(url, expiration)

            val filePath = url.substringAfter("future-prescanned:").substringBefore('?')
            val safe = serializer().ready.then(filePath)
            val newItem = UploadForNextRequest(
                expires = now().plus(expiration),
                file = ServerFile(safe.url)
            )
            database().table<UploadForNextRequest>().insertOne(newItem)

            url
        }
    )

    public val cleanupSchedule: ScheduledTask = path.path("cleanupUploads") bind ScheduledTask(frequency = 1.days) {
        database().table<UploadForNextRequest>().deleteMany(condition { it.expires lt now() }).forEach {
            try {
                files().parseInternalUrl(it.file.location)!!.delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}