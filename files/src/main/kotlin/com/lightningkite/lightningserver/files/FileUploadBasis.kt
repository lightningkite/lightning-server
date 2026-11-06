package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.encryption.signerBlocking
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.engine
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.services.database.*
import com.lightningkite.services.files.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * Holds what every early upload shares: the tokens, the ServerFile serializer, the tracking table and
 * its cleanup. It declares no endpoints of its own; [UploadEndpoints] modules declare those.
 *
 * Include it once, then give each place that accepts uploads its own [UploadEndpoints]:
 * ```kotlin
 * val uploads = path.path("uploads") include UploadBasis(files, database, fileScanner = { listOf() })
 * val avatarUpload = path.path("users").path("avatar-upload") module
 *     UploadEndpoints(uploads, "users/avatars", UserAuth.require())
 * ```
 *
 * Flow:
 * - Client calls a [UploadEndpoints.upload] to obtain an [UploadTarget]: an upload URL and an [UnverifiedUploadToken].
 * - Client uploads the file (PUT) to uploadUrl.
 * - Client calls that module's [UploadEndpoints.verify], which scans the file and returns a token
 *   naming the scanned copy.
 * - Client includes that token as a serialized ServerFile in a later request.
 *
 * Gotchas:
 * - When file scanners are configured, verify is **required**, not an optimization: a token for an
 *   unscanned upload is rejected during deserialization. With no scanners configured there is nothing
 *   to scan, so the first token is already usable and verify is a no-op.
 * - The file system must sign the references it issues whenever scanners are configured, or a client
 *   can simply name the jail file instead of verifying it; this is checked, and fails loudly.
 * - Turning scanning on is not retroactive. Tokens minted while no scanners were configured name
 *   files the client uploaded straight to the ready location, and stay usable until they expire
 *   ([expiration]), so scanning only covers everything once the outstanding ones have aged out.
 * - Upload tokens are time-limited by [expiration]. Ensure your client uses them promptly.
 * - This installs the contextual ServerFile serializer, so a server should include only one basis.
 */
public class FileUploadBasis(
    public val files: Runtime<ExternalFileSystem>,
    public val database: Runtime<Database>,
    public val fileScanner: Runtime<List<FileScanner>>,
    public val jailFilePath: String = "upload-jail",
    public val filePath: String = "uploaded",
    public val expiration: Duration = 1.days,
    public val foreignUrlHandling: ForeignUrlHandling = ForeignUrlHandling.ERROR,
) : ServerBuilder() {

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
     * scanners are configured, which is what lets verify freeze a file's bytes by copying here.
     */
    private val ready: Runtime<ExternalFile> = Runtime.Cached { checkedFiles().root.then(filePath) }

    private val uploadSigner: Runtime<Signer> = secretBasis.signerBlocking("upload-files")

    /** Mints and checks the tokens handed to clients. */
    public val tokens: Runtime<UploadTokens> = Runtime.Cached { UploadTokens(uploadSigner(), engine.clock) }

    /**
     * Contextual serializer used for ServerFile values.
     *
     * It resolves this basis's tokens and nothing else: it holds no scanners and cannot name the
     * jail location, so it has no way to turn an unscanned upload into a usable ServerFile.
     *
     * That only closes the token path. A client can still submit a plain URL, which falls through to
     * [ExternalFileSystem.parseExternalUrl] - safe while the file system signs its URLs, and not safe
     * when it does not, since an unsigned backend accepts any path a client cares to name, jail
     * included. Configure the file system with signing on whenever scanners are in use.
     */
    public val serializer: Runtime<ExternalServerFileSerializer> = Runtime.Cached {
        val ready = ready()
        val tokens = tokens()
        val table = uploadForNextRequestTable()
        ExternalServerFileSerializer(
            fileSystems = listOf(checkedFiles()),
            foreignUrlHandling = foreignUrlHandling,
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
                            table.deleteManyIgnoringOld(Condition { it.file eq file.serverFile })
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

    internal val fileScanFailedError = LSError(
        http = HttpStatus.BadRequest.code,
        detail = "file-scan-failed",
        message = "The uploaded file was rejected by a file scanner.",
    )

    // Claude: an empty folder keeps keys at the root, which is where UploadEarlyEndpoint has always put them.
    private fun newKey(folder: String): String =
        if (folder.isEmpty()) "${Uuid.random()}.file" else "$folder/${Uuid.random()}.file"

    /** Returns an upload URL and token for a new file under [folder]. */
    context(_: ServerRuntime)
    internal suspend fun prepare(folder: String): UploadTarget {
        val key = newKey(folder)
        // With no scanners there is nothing to promote, so the client uploads straight to the
        // ready location and its token is born scanned.
        val nothingToScan = fileScanner().isEmpty()
        val target = (if (nothingToScan) ready() else jail()).then(key)
        uploadForNextRequestTable().insertOne(
            UploadForNextRequest(expires = now().plus(expiration), file = target.serverFile)
        )
        return UploadTarget(
            uploadUrl = target.uploadUrl(expiration),
            token = UnverifiedUploadToken(
                tokens().sign(
                    if (nothingToScan) UploadToken.Scanned(key) else UploadToken.Unscanned(key),
                    expiration,
                )
            ),
        )
    }

    /**
     * Scans the file [upload] names and promotes it to the ready location, keeping it in the folder it was uploaded to.
     *
     * Returns a [ServerFile] whose location is the scanned token, not a URL, so it must be encoded with
     * [DirectServerFileSerializer]; the contextual serializer resolves it only when a later request sends it back.
     */
    context(_: ServerRuntime)
    internal suspend fun verify(upload: UnverifiedUploadToken): ServerFile = when (
        val parsed = tokens().parseOrNull(upload.raw) ?: throw IllegalArgumentException("Not an upload token.")
    ) {
        // Already verified. Returning the token unchanged makes a retry safe.
        is UploadToken.Scanned -> ServerFile(upload.raw)

        is UploadToken.Unscanned -> {
            // Promote under a fresh server-chosen key rather than reusing the client's. The
            // jail stays writable through the presigned upload URL for the whole expiration
            // window, so a client that re-uploads and verifies again would otherwise
            // overwrite - or, when the rescan fails, delete - a file it had already had
            // certified and handed out. Distinct keys make every certified file immutable.
            // Claude: the token is signed, so its folder is one this server chose; keep it.
            val promotedKey = newKey(parsed.key.substringBeforeLast('/', ""))
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
                // A scanner rejecting the file is the client's fault, not ours.
                if (e is FileScanException) throw BadRequestException(
                    detail = fileScanFailedError.detail,
                    message = e.message ?: fileScanFailedError.message,
                    cause = e,
                )
                throw e
            }

            // The jail copy is deliberately left in place: its own row expires and the
            // cleanup task collects it, and leaving it lets a client that lost our response
            // retry without re-uploading.
            ServerFile(tokens().sign(UploadToken.Scanned(promotedKey), expiration))
        }
    }

    /**
     * Daily cleanup of expired uploads. Removes database entries and attempts to delete the associated file.
     */
    public val cleanupSchedule: ScheduledTask = path.path("cleanupUploads") bind ScheduledTask(frequency = 1.days) {
        uploadForNextRequestTable().deleteMany(UploadForNextRequest.expires lt now()).forEach {
            try {
                it.file.externalFile.delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
