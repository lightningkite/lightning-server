package com.lightningkite.lightningserver.files



interface Api {
	fun withHeaderCalculator(calculator: suspend () -> List<Pair<String, String>>): Api

	interface UploadEarlyEndpointApi {
		/**
		 * Upload File for Request
		 * 
		 * Upload a file to make a request later.  Times out in around 10 minutes.
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		suspend fun uploadFileForRequest(): com.lightningkite.lightningserver.files.UploadInformation
		/**
		 * Verify uploaded file
		 * 
		 * Checks out a file and moves it out of jail if it's safe.  Makes for significantly faster subsequent requests.
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		suspend fun verifyUploadedFile(input: kotlin.String): kotlin.String
	}
	val uploadEarlyEndpoint: UploadEarlyEndpointApi

	val module: com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.files.UploadEarlySdkTests.Model, kotlin.uuid.Uuid>
}
