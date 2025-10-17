package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.typed.Fetcher
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable

class LiveApi(val fetcher: Fetcher) : Api {
	override fun withHeaderCalculator(calculator: suspend () -> List<Pair<String, String>>): LiveApi = 
		LiveApi(fetcher.withHeaderCalculator(calculator))

	inner class LiveUploadEarlyEndpointApi : Api.UploadEarlyEndpointApi {
		override suspend fun uploadFileForRequest(): com.lightningkite.lightningserver.files.UploadInformation =
			fetcher("upload", HttpMethod.GET, kotlin.Unit.serializer(), kotlin.Unit, com.lightningkite.lightningserver.files.UploadInformation.serializer())
		override suspend fun verifyUploadedFile(input: kotlin.String): kotlin.String =
			fetcher("upload/verify", HttpMethod.POST, kotlin.String.serializer(), input, kotlin.String.serializer())
	}
	override val uploadEarlyEndpoint = LiveUploadEarlyEndpointApi()

	override val module = com.lightningkite.lightningserver.typed.LiveClientModelRestEndpoints(fetcher, "module/rest", com.lightningkite.lightningserver.files.UploadEarlySdkTests.Model.serializer(), kotlin.uuid.Uuid.serializer())
}
