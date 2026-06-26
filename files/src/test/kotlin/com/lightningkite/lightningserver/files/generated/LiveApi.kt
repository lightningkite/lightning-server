package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.typed.Fetcher
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.builtins.*
import kotlinx.serialization.ExperimentalSerializationApi

@OptIn(ExperimentalSerializationApi::class)
class LiveApi(val fetcher: Fetcher) : Api {
	override fun withHeaderCalculator(calculator: suspend () -> List<Pair<String, String>>): LiveApi = 
		LiveApi(fetcher.withHeaderCalculator(calculator))

	override val uploadEarlyEndpoint = com.lightningkite.lightningserver.files.LiveClientUploadEarlyEndpoints(fetcher, "upload", )

	override val module = com.lightningkite.lightningserver.typed.LiveClientModelRestEndpoints(fetcher, "module/rest", com.lightningkite.lightningserver.files.UploadEarlySdkTests.Model.serializer(), kotlin.uuid.Uuid.serializer())
}
