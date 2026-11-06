package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.explicitApiHttpHandler
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.services.database.*
import com.lightningkite.services.files.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

@Deprecated("Renamed to better reflect its purpose", ReplaceWith("GlobalUploadEndpoints", "com.lightningkite.lightningserver.files.GlobalUploadEndpoints"))
public typealias UploadEarlyEndpoint = GlobalUploadEndpoints

/**
 * Provides a single endpoint pair for early file upload, storing every file at the root of [filePath].
 *
 * See [UploadBasis] for the flow and its gotchas. To give each feature its own folder and permission
 * check instead, include an [UploadBasis] and declare a [UploadEndpoints] per feature.
 */
public class GlobalUploadEndpoints(
    public val files: Runtime<ExternalFileSystem>,
    public val database: Runtime<Database>,
    public val fileScanner: Runtime<List<FileScanner>>,
    public val jailFilePath: String = "upload-jail",
    public val filePath: String = "uploaded",
    public val expiration: Duration = 1.days,
    public val authOptions: AuthRequirement<*> = noAuth,
    public val foreignUrlHandling: ForeignUrlHandling = ForeignUrlHandling.ERROR,
) : ServerBuilder() {
    init {
        sdkSettings.clientInterface = ClientFileUploadEndpoints::class.info()
    }

    public val basis: UploadBasis = path include UploadBasis(
        files = files,
        database = database,
        fileScanner = fileScanner,
        jailFilePath = jailFilePath,
        filePath = filePath,
        expiration = expiration,
        foreignUrlHandling = foreignUrlHandling,
    )

    /** Mints and checks the tokens this endpoint hands to clients. */
    public val tokens: Runtime<UploadTokens> get() = basis.tokens

    /** Contextual serializer used for ServerFile values; see [UploadBasis.serializer]. */
    public val serializer: Runtime<ExternalServerFileSerializer> get() = basis.serializer

    /**
     * GET handler to prepare an upload. Returns an [UploadTarget] containing the presigned upload URL and
     * the [UnverifiedUploadToken] to pass to [verify].
     */
    public val endpoint: ApiHttpHandler<PathSpec0, HasId<*>?, Unit, UploadTarget> =
        path.get bind ApiHttpHandler(
            auth = authOptions,
            summary = "Upload File for Request",
            functionName = "getFileUploadUrl",
            description = "Upload a file to make a request later. Times out in $expiration.",
            errorCases = listOf(),
            implementation = { _: Unit -> basis.prepare(folder = "") }
        )

    /**
     * POST handler to verify a previously uploaded file, scanning it and promoting it to the ready
     * location. Returns a [ServerFile] to send in a later request.
     */
    public val verify: ApiHttpHandler<PathSpec0, HasId<*>?, UnverifiedUploadToken, ServerFile> =
        path.path("verify").post bind explicitApiHttpHandler(
            auth = authOptions,
            inputType = UnverifiedUploadToken.serializer(),
            // Claude: the location is the scanned token; the contextual serializer would send a signed URL instead,
            // which the server would never claim, so cleanup would delete the file.
            outputType = DirectServerFileSerializer,
            summary = "Verify uploaded file",
            functionName = "verifyUpload",
            description = "Scans an uploaded file and moves it out of jail so later requests can use it.",
            errorCases = listOf(basis.fileScanFailedError),
            implementation = { upload: UnverifiedUploadToken -> basis.verify(folder = "", upload) }
        )

    /** Daily cleanup of expired uploads; see [UploadBasis.cleanupSchedule]. */
    public val cleanupSchedule: ScheduledTask get() = basis.cleanupSchedule
}
