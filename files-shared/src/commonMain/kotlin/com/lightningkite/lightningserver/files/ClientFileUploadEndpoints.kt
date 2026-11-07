package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.typed.Fetcher
import com.lightningkite.lightningserver.typed.LiveVersion
import com.lightningkite.services.files.DirectServerFileSerializer
import com.lightningkite.services.files.ServerFile
import kotlinx.serialization.builtins.serializer

/**
 * Client for a server's `UploadEndpoints` or `GlobalUploadEndpoints` module.
 *
 * To send a file: call [getFileUploadUrl], PUT the bytes to [UploadTarget.uploadUrl], pass
 * [UploadTarget.token] to [verifyUpload], then put the [ServerFile] it returns wherever a request
 * expects a file.
 */
@LiveVersion(LiveClientFileUploadEndpoints::class)
public interface ClientFileUploadEndpoints {
    /**
     * Returns a URL to PUT a file to, and a token that refers to that file.
     *
     * Both expire, usually within a day; upload and verify promptly.
     */
    public suspend fun getFileUploadUrl(): UploadTarget

    /**
     * Scans the file [upload] refers to, and returns it as a [ServerFile] a later request can use.
     *
     * It expires like the upload it came from, so use it promptly.
     *
     * Verify every upload, even on servers with no scanners: there the call is a cheap no-op.
     */
    public suspend fun verifyUpload(upload: UnverifiedUploadToken): ServerFile

    @Suppress("DEPRECATION")
    @Deprecated("Renamed to getFileUploadUrl.", ReplaceWith("getFileUploadUrl()"))
    public suspend fun uploadFileForRequest(): UploadInformation =
        getFileUploadUrl().let { UploadInformation(it.uploadUrl, it.token.raw) }

    @Deprecated("Use verifyUpload.", ReplaceWith("verifyUpload(UnverifiedUpload(input)).location"))
    public suspend fun verifyUploadedFile(input: String): String = verifyUpload(UnverifiedUploadToken(input)).location
}

public open class LiveClientFileUploadEndpoints(
    public val fetcher: Fetcher,
    public val subpath: String,
) : ClientFileUploadEndpoints {
    override suspend fun getFileUploadUrl(): UploadTarget =
        fetcher(subpath, HttpMethod.GET, Unit.serializer(), Unit, UploadTarget.serializer())

    override suspend fun verifyUpload(upload: UnverifiedUploadToken): ServerFile =
        fetcher("$subpath/verify", HttpMethod.POST, UnverifiedUploadToken.serializer(), upload, DirectServerFileSerializer)
}

@Deprecated("Renamed", ReplaceWith("com.lightningkite.lightningserver.files.ClientFileUploadEndpoints"))
public typealias ClientUploadEarlyEndpoints = ClientFileUploadEndpoints

@Deprecated("Renamed", ReplaceWith("com.lightningkite.lightningserver.files.LiveClientFileUploadEndpoints"))
public typealias LiveClientUploadEarlyEndpoints = LiveClientFileUploadEndpoints
