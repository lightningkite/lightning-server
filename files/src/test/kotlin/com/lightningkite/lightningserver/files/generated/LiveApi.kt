package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.typed.Fetcher
import com.lightningkite.lightningserver.typed.RequestMetadata
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.builtins.*
import kotlinx.serialization.ExperimentalSerializationApi

@OptIn(ExperimentalSerializationApi::class)
class LiveApi(val fetcher: Fetcher) : Api {
	override fun withMetadata(block: suspend RequestMetadata.() -> Unit): LiveApi =
		LiveApi(fetcher.withMetadata(block))

	override val uploadEarlyEndpoint = com.lightningkite.lightningserver.files.LiveClientUploadEarlyEndpoints(fetcher, "upload", )

	override val module = com.lightningkite.lightningserver.typed.LiveClientModelRestEndpoints(fetcher, "module/rest", com.lightningkite.lightningserver.files.UploadEarlySdkTests.Model.serializer(), kotlin.uuid.Uuid.serializer())
}
