package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.typed.Fetcher
import kotlinx.serialization.builtins.serializer
import com.lightningkite.lightningserver.typed.urlifyToCommaString

interface Api {

	interface UploadEarlyEndpointApi {
		suspend fun uploadFileForRequest(): com.lightningkite.lightningserver.files.UploadInformation
		suspend fun verifyUploadedFile(input: kotlin.String): kotlin.String
	}
	val uploadEarlyEndpoint: UploadEarlyEndpointApi

	val module: com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.files.UploadEarlySdkTests.Model, kotlin.uuid.Uuid>
}
