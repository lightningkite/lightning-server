package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.explicitApiHttpHandler
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.services.database.HasId
import com.lightningkite.services.files.DirectServerFileSerializer
import com.lightningkite.services.files.ServerFile

/**
 * Upload endpoints that store files under [folder], for callers passing [authOptions].
 *
 * Tokens, the ServerFile serializer and cleanup come from the shared [basis], so a file uploaded here
 * is used in later requests like any other upload. Declare one of these wherever a feature accepts
 * uploads, and put the feature's permission check in [authOptions], e.g.
 * `UserAuth.require { it.fetch().canUploadDocuments }`.
 *
 * @param folder The path under the basis's jail and ready locations, such as `"users/avatars"`.
 */
public class UploadEndpoints(
    public val basis: UploadBasis,
    public val folder: String,
    public val authOptions: AuthRequirement<*>,
) : ServerBuilder() {
    init {
        require(folder.isNotBlank() && !folder.startsWith('/') && !folder.endsWith('/')) {
            "Upload folder must be a relative path without leading or trailing slashes; got '$folder'."
        }
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
    public val upload: ApiHttpHandler<PathSpec0, HasId<*>?, Unit, UploadTarget> =
        path.get bind ApiHttpHandler(
            auth = authOptions,
            summary = "Get File Upload URL",
            functionName = "getFileUploadUrl",
            description = "Returns a URL to PUT a file to, and a token that refers to that file. Send the token to verify, " +
                "then use the result as a file in a later request. Both expire in ${basis.expiration}.",
            errorCases = listOf(),
            implementation = { _: Unit -> basis.prepare(folder) }
        )

    /**
     * POST handler to verify a file uploaded through [upload], scanning it and promoting it to the ready
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
            implementation = { upload: UnverifiedUploadToken -> basis.verify(folder, upload) }
        )
}
