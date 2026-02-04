package com.lightningkite.lightningserver.files



interface Api {
	fun withHeaderCalculator(calculator: suspend () -> List<Pair<String, String>>): Api

	val uploadEarlyEndpoint: com.lightningkite.lightningserver.files.ClientUploadEarlyEndpoints

	val module: com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.files.UploadEarlySdkTests.Model, kotlin.uuid.Uuid>
}
