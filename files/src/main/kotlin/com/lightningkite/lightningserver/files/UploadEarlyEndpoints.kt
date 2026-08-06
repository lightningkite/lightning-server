package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.HMAC_Blocking
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.services.database.*
import com.lightningkite.services.files.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * Provides an opinionated set of endpoints for client-side early file upload and later usage within API calls.
 *
 * Flow:
 * - Client calls [endpoint] to obtain an uploadUrl and a futureCallToken.
 * - Client uploads the file (PUT) to uploadUrl.
 * - Optionally, client calls [verify] to scan and move file out of jail for faster subsequent requests.
 * - Client includes futureCallToken as a serialized ServerFile in a later request.
 *
 * Gotchas:
 * - When file scanners are configured, initial uploads go to a jailed location and must be scanned/moved before use.
 * - The futureCallToken is time-limited by [expiration]. Ensure your client uses it promptly.
 */
public class UploadEarlyEndpoint(
    public val files: Runtime<ExternalFileSystem>,
    public val database: Runtime<Database>,
    public val fileScanner: Runtime<List<FileScanner>>,
    public val jailFilePath: String = "upload-jail",
    public val filePath: String = "uploaded",
    public val expiration: Duration = 1.days,
    public val authOptions: AuthRequirement<*> = noAuth,
) : ServerBuilder() {
    init {
        sdkSettings.clientInterface = ClientUploadEarlyEndpoints::class.info()
    }

    // Defines the table, registers it, and creates its once-per-deploy prepare task.
    private val uploadForNextRequestTable = database.registerTable<UploadForNextRequest>("UploadForNextRequest")

    /**
     * Contextual serializer used for ServerFile values that integrates with the configured ExternalFileSystem
     * and scanners to produce signed URLs, enforce jail/ready flows, and clean up single-use records.
     */
    public val serializer: Runtime<ExternalServerFileSerializer> = Runtime.Cached {
        ExternalServerFileSerializer(
            clock = serverRuntime.clock,
            scanners = fileScanner(),
            jail = files().root.then(jailFilePath),
            ready = files().root.then(filePath),
            fileSystems = listOf(files()),
            onUse = { fileObject ->
                runBlocking {
                    uploadForNextRequestTable()
                        .deleteManyIgnoringOld(condition { it.file eq fileObject.serverFile })
                }
            },
            key = secretBasis().HMAC_Blocking("upload-files")
        )
    }

    /**
     * Registers [serializer] as the contextual serializer for ServerFile so that endpoints and tests can
     * encode/decode values without having to pass a serializer explicitly.
     */
    override val externalSerialization: Runtime<SerializersModule> = Runtime.Cached {
        SerializersModule {
            contextual(ServerFile::class, serializer())
        }
    }

    /**
     * GET handler to prepare an upload. Returns [UploadInformation] containing the presigned upload URL and
     * the token to reference the file in a subsequent call.
     */
    public val endpoint: ApiHttpHandler<PathSpec0, HasId<*>?, Unit, UploadInformation> =
        path.get bind ApiHttpHandler(
            auth = authOptions,
            summary = "Upload File for Request",
            description = "Upload a file to make a request later. Times out in $expiration.",
            errorCases = listOf(),
            implementation = { _: Unit ->
                val key = "${Uuid.random()}.file"
                if (fileScanner().isEmpty()) {
                    val newFile = serializer().ready.then(key)
                    val newItem = UploadForNextRequest(
                        expires = now().plus(expiration),
                        file = newFile.serverFile
                    )
                    uploadForNextRequestTable().insertOne(newItem)
                    UploadInformation(
                        uploadUrl = newFile.uploadUrl(expiration),
                        futureCallToken = serializer().certifyAlreadyScannedForUse(key, expiration)
                    )
                } else {
                    val newFile = serializer().jail.then(key)
                    val newItem = UploadForNextRequest(
                        expires = now().plus(expiration),
                        file = newFile.serverFile
                    )
                    uploadForNextRequestTable().insertOne(newItem)
                    UploadInformation(
                        uploadUrl = newFile.uploadUrl(expiration),
                        futureCallToken = serializer().certifyForUse(key, expiration)
                    )
                }
            }
        )

    /**
     * POST handler to verify a previously uploaded file, scanning and moving it to the ready location if safe.
     * Returns a URL that can be decoded as a ServerFile for later use.
     */
    public val verify: ApiHttpHandler<PathSpec0, HasId<*>?, String, String> =
        path.path("verify").post bind ApiHttpHandler(
            auth = authOptions,
            summary = "Verify uploaded file",
            description = "Checks out a file and moves it out of jail if it's safe.  Makes for significantly faster subsequent requests.",
            errorCases = listOf(),
            implementation = { url: String ->
                // TODO: Avoid shadowing the 'url' parameter with a new local 'url' variable; consider using a different name for clarity.
                val url = serializer().scan(url, expiration)

                // TODO: Avoid relying on magic string prefix 'future-prescanned:'; prefer a structured token or helper method to extract the path.
                val filePath = url.substringAfter("future-prescanned:").substringBefore('?')
                val safe = serializer().ready.then(filePath)
                val newItem = UploadForNextRequest(
                    expires = now().plus(expiration),
                    file = safe.serverFile
                )
                uploadForNextRequestTable().insertOne(newItem)

                url
            }
        )

    /**
     * Daily cleanup of expired uploads. Removes database entries and attempts to delete the associated file.
     */
    public val cleanupSchedule: ScheduledTask = path.path("cleanupUploads") bind ScheduledTask(frequency = 1.days) {
        uploadForNextRequestTable().deleteMany(condition { it.expires lt now() }).forEach {
            try {
                it.file.externalFile.delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /*
    TODO(API):
    - Consider making expiration configurable per-request to support large uploads with client-chosen windows (within limits).
    - Provide hooks for custom quarantine/jail policies beyond simple FileScanner list.
    - Clarify the shape of futureCallToken in docs and expose a type-safe wrapper instead of a raw String.
    */
}
