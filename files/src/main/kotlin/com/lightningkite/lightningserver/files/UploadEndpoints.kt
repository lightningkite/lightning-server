package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.lightningserver.typed.explicitApiHttpHandler
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.services.data.Unsafe
import com.lightningkite.services.database.HasId
import com.lightningkite.services.files.DirectServerFileSerializer
import com.lightningkite.services.files.ServerFile

/**
 * Creates upload endpoints that store every file under the fixed [folder].
 *
 * @throws IllegalArgumentException if [folder] is not a valid folder; see [requireValidRelativeFolderPath].
 */
public fun <USER : HasId<*>?> UploadEndpoints(
    basis: FileUploadBasis,
    folder: String,
    auth: AuthRequirement<USER>
): UploadEndpoints<USER> {
    requireValidRelativeFolderPath(folder)
    return UploadEndpoints(basis, auth) { folder }
}

/**
 * Upload endpoints that store files under the folder [folder] returns, for callers passing [auth].
 *
 * Tokens, the ServerFile serializer and cleanup come from the shared [basis], so a file uploaded here
 * is used in later requests like any other upload. Declare one of these wherever a feature accepts
 * uploads, and put the feature's permission check in [auth], e.g.
 * `UserAuth.require { it.fetch().canUploadDocuments }`.
 *
 * [folder] runs on every upload, so it can sort files by who is uploading:
 * ```kotlin
 * UploadEndpoints(uploads, UserAuth.require()) { "users/${auth.rawId}/avatars" }
 * ```
 * It must return a valid folder (see [requireValidRelativeFolderPath]); anything else fails the upload with an
 * [IllegalArgumentException].
 */
public class UploadEndpoints<USER : HasId<*>?>(
    public val basis: FileUploadBasis,
    public val auth: AuthRequirement<USER>,
    private val folder: suspend context(ServerRuntime) AuthAccess<USER>.() -> String,
) : ServerBuilder() {
    init {
        sdkSettings.clientInterface = ClientFileUploadEndpoints::class.info()
        sdkSettings.defaultInfo = SdkModule.Info(
            interfaceName = "FileUploadEndpoints",
            valueName = "files"
        )
    }

    /**
     * GET handler to prepare an upload. Returns an [UploadTarget] containing the presigned upload URL and
     * the [UnverifiedUploadToken] to pass to [verify].
     */
    @OptIn(Unsafe::class) // SAFETY: auth is required above and the folder comes from server code, not the client.
    public val upload: ApiHttpHandler<PathSpec0, USER, Unit, UploadTarget> =
        path.get bind ApiHttpHandler(
            auth = auth,
            summary = "Get File Upload URL",
            functionName = "getFileUploadUrl",
            description = "Returns a URL to PUT a file to, and a token that refers to that file. Send the token to verify, " +
                "then use the result as a file in a later request. Both expire in ${basis.expiration}.",
            errorCases = listOf(),
            implementation = { _: Unit ->
                basis.prepare(folder())
            }
        )

    /**
     * POST handler to verify a file uploaded through [upload], scanning it and promoting it to the ready
     * location. Returns a [ServerFile] to send in a later request.
     */
    @OptIn(Unsafe::class) // SAFETY: the output is encoded with DirectServerFileSerializer.
    public val verify: ApiHttpHandler<PathSpec0, USER, UnverifiedUploadToken, ServerFile> =
        path.path("verify").post bind explicitApiHttpHandler(
            auth = auth,
            inputType = UnverifiedUploadToken.serializer(),
            // Claude: the location is the scanned token; the contextual serializer would send a signed URL instead,
            // which the server would never claim, so cleanup would delete the file.
            outputType = DirectServerFileSerializer,
            summary = "Verify uploaded file",
            functionName = "verifyUpload",
            description = "Scans an uploaded file and moves it out of jail so later requests can use it.",
            errorCases = listOf(basis.fileScanFailedError),
            implementation = { upload: UnverifiedUploadToken -> basis.verify(upload) }
        )
}

/**
 * Throws an [IllegalArgumentException] if [folder] is not a valid upload folder.
 *
 * A valid folder is either `""`, meaning the root, or a relative path such as `"users/avatars"`: no leading or
 * trailing `/`, and no blank, `.` or `..` segments.
 */
public fun requireValidRelativeFolderPath(folder: String) {
    require(folder.isEmpty() || folder.split('/').none { it.isBlank() || it == "." || it == ".." }) {
        "Upload folder must be \"\" or a relative path with no empty, '.' or '..' segments; got '$folder'."
    }
}
