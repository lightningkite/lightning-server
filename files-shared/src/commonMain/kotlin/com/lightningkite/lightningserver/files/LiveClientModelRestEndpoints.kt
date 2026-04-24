package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.typed.Fetcher
import kotlinx.serialization.builtins.serializer

public open class LiveClientUploadEarlyEndpoints(
    public val fetcher: Fetcher,
    public val subpath: String,
) : ClientUploadEarlyEndpoints {
    override suspend fun uploadFileForRequest(): com.lightningkite.lightningserver.files.UploadInformation =
        fetcher(
            subpath,
            HttpMethod.GET,
            kotlin.Unit.serializer(),
            kotlin.Unit,
            com.lightningkite.lightningserver.files.UploadInformation.serializer()
        )

    override suspend fun verifyUploadedFile(input: kotlin.String): kotlin.String =
        fetcher("$subpath/verify", HttpMethod.POST, kotlin.String.serializer(), input, kotlin.String.serializer())
}
