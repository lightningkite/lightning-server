package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signerBlocking
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
import kotlinx.coroutines.CancellationException
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
 * - Client calls [verify], which scans the file and returns a token naming the scanned copy.
 * - Client includes that token as a serialized ServerFile in a later request.
 *
 * Gotchas:
 * - When file scanners are configured, [verify] is **required**, not an optimization: a token for an
 *   unscanned upload is rejected during deserialization. With no scanners configured there is nothing
 *   to scan, so the first token is already usable and [verify] is a no-op.
 * - The file system must sign the references it issues whenever scanners are configured, or a client
 *   can simply name the jail file instead of verifying it; this is checked, and fails loudly.
 * - Turning scanning on is not retroactive. Tokens minted while no scanners were configured name
 *   files the client uploaded straight to the ready location, and stay usable until they expire
 *   ([expiration]), so scanning only covers everything once the outstanding ones have aged out.
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
     * The file system, with the precondition the jail/ready split depends on checked once before
     * anything uses it.
     *
     * Everything here resolves the file system through this rather than through [files], so no entry
     * point can reach storage without the check having run.
     */
    private val checkedFiles: Runtime<ExternalFileSystem> = Runtime.Cached {
        files().also {
            require(fileScanner().isEmpty() || it.referencesAreUnforgeable) {
                "File scanning cannot be enforced on file system '${it.name}': it does not sign the " +
                    "references it issues, so a client can name the unscanned jail file directly " +
                    "rather than going through the verify endpoint. Give it a signedUrlDuration, or " +
                    "configure no scanners."
            }
        }
    }

    /** Where a client's presigned upload lands while it is still untrusted. */
    private val jail: Runtime<ExternalFile> = Runtime.Cached { checkedFiles().root.then(jailFilePath) }

    /**
     * Where scanned files live. Nothing hands out a presigned upload URL for this location while
     * scanners are configured, which is what lets [verify] freeze a file's bytes by copying here.
     */
    private val ready: Runtime<ExternalFile> = Runtime.Cached { checkedFiles().root.then(filePath) }

    private val uploadSigner: Runtime<Signer> = secretBasis.signerBlocking("upload-files")

    /** Mints and checks the tokens this endpoint hands to clients. */
    public val tokens: Runtime<UploadTokens> = Runtime.Cached { UploadTokens(uploadSigner(), serverRuntime.clock) }

    /**
     * Contextual serializer used for ServerFile values.
     *
     * It resolves this endpoint's tokens and nothing else: it holds no scanners and cannot name the
     * jail location, so it has no way to turn an unscanned upload into a usable ServerFile.
     *
     * That only closes the token path. A client can still submit a plain URL, which falls through to
     * [ExternalFileSystem.parseExternalUrl] - safe while the file system signs its URLs, and not safe
     * when it does not, since an unsigned backend accepts any path a client cares to name, jail
     * included. Configure this endpoint's file system with signing on whenever scanners are in use.
     */
    public val serializer: Runtime<ExternalServerFileSerializer> = Runtime.Cached {
        val ready = ready()
        val tokens = tokens()
        val table = uploadForNextRequestTable()
        ExternalServerFileSerializer(
            fileSystems = listOf(checkedFiles()),
            resolveUpload = { raw ->
                when (val token = tokens.parseOrNull(raw)) {
                    null -> null

                    is UploadToken.Unscanned -> throw IllegalArgumentException(
                        "This file has not been scanned yet. Send its token to the verify endpoint and use what that returns."
                    )

                    is UploadToken.Scanned -> ready.then(token.key).also { file ->
                        // The row exists only to keep the file alive until something claims it; now that
                        // something has, drop it so the cleanup task doesn't delete a file in use.
                        runBlocking {
                            table.deleteManyIgnoringOld(condition { it.file eq file.serverFile })
                        }
                    }
                }
            },
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
                // With no scanners there is nothing to promote, so the client uploads straight to the
                // ready location and its token is born scanned.
                val nothingToScan = fileScanner().isEmpty()
                val target = (if (nothingToScan) ready() else jail()).then(key)
                uploadForNextRequestTable().insertOne(
                    UploadForNextRequest(expires = now().plus(expiration), file = target.serverFile)
                )
                UploadInformation(
                    uploadUrl = target.uploadUrl(expiration),
                    futureCallToken = tokens().sign(
                        if (nothingToScan) UploadToken.Scanned(key) else UploadToken.Unscanned(key),
                        expiration,
                    )
                )
            }
        )

    /**
     * POST handler to verify a previously uploaded file, scanning it and promoting it to the ready
     * location. Returns a token that can be decoded as a ServerFile in a later request.
     */
    public val verify: ApiHttpHandler<PathSpec0, HasId<*>?, String, String> =
        path.path("verify").post bind ApiHttpHandler(
            auth = authOptions,
            summary = "Verify uploaded file",
            description = "Scans an uploaded file and moves it out of jail so later requests can use it.",
            errorCases = listOf(),
            implementation = { token: String ->
                val parsed = tokens().parseOrNull(token)
                    ?: throw IllegalArgumentException("Not an upload token.")
                when (parsed) {
                    // Already verified. Returning the token unchanged makes a retry safe.
                    is UploadToken.Scanned -> token

                    is UploadToken.Unscanned -> {
                        // Promote under a fresh server-chosen key rather than reusing the client's. The
                        // jail stays writable through the presigned upload URL for the whole expiration
                        // window, so a client that re-uploads and verifies again would otherwise
                        // overwrite - or, when the rescan fails, delete - a file it had already had
                        // certified and handed out. Distinct keys make every certified file immutable.
                        val promotedKey = "${Uuid.random()}.file"
                        val safe = ready().then(promotedKey)

                        // Record the file before creating it. The cleanup task collects by row, so
                        // inserting first means a crash anywhere below leaves a tracked file rather
                        // than an orphan nothing will ever delete.
                        uploadForNextRequestTable().insertOne(
                            UploadForNextRequest(expires = now().plus(expiration), file = safe.serverFile)
                        )

                        // Copy first, then scan the copy - never scan the jail file in place, because
                        // the client could swap the bytes afterwards through that same upload URL.
                        // Nothing can write to the ready location, so once copied the bytes are frozen
                        // and the scan below covers exactly what will later be served.
                        jail().then(parsed.key).copyTo(safe)
                        try {
                            fileScanner().scan(safe)
                        } catch (cancellation: CancellationException) {
                            // The cleanup below is suspend work, which fails immediately in a cancelled
                            // scope and would only bury the cancellation under a suppressed exception.
                            // The row inserted above already guarantees the file gets collected.
                            throw cancellation
                        } catch (e: Exception) {
                            // Nothing can reference an unscanned ready file - only the line below mints
                            // a Scanned token, and promotedKey is unguessable - so this is prompt
                            // hygiene rather than the thing keeping us safe. Suppress rather than mask:
                            // the scan failure is the error worth reporting.
                            try {
                                safe.delete()
                            } catch (cleanupFailure: Exception) {
                                e.addSuppressed(cleanupFailure)
                            }
                            throw e
                        }

                        // The jail copy is deliberately left in place: its own row expires and the
                        // cleanup task collects it, and leaving it lets a client that lost our response
                        // retry without re-uploading.
                        tokens().sign(UploadToken.Scanned(promotedKey), expiration)
                    }
                }
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
    - Expose a type-safe wrapper for futureCallToken instead of a raw String on the wire.
    */
}
