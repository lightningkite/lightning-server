package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.typed.RequestMetadata

interface Api {
	fun withMetadata(block: suspend RequestMetadata.() -> Unit): Api
	@Deprecated("Use withMetadata, which can also keep or remove existing metadata", ReplaceWith("withMetadata { clear(); for ((name, value) in calculator()) append(name, value) }"))
	fun withHeaderCalculator(calculator: suspend () -> List<Pair<String, String>>): Api =
		withMetadata { clear(); for ((name, value) in calculator()) append(name, value) }

	val uploadEarlyEndpoint: com.lightningkite.lightningserver.files.ClientUploadEarlyEndpoints

	val module: com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.files.UploadEarlySdkTests.Model, kotlin.uuid.Uuid>
}
