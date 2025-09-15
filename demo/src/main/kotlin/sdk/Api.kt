package com.lightningkite.lightningserver.demo



interface Api {
	/**
	 * Get Test Object
	 * 
	 * **Auth Requirements:** User with scope *
	 * */
	suspend fun getTestObject(): com.lightningkite.lightningserver.demo.TestModel
	/**
	 * Get Server Health
	 * 
	 * Gets the current status of the server
	 * 
	 * **Auth Requirements:** Not Authenticated
	 * */
	suspend fun getServerHealth(): com.lightningkite.lightningserver.typed.ServerHealth
	/**
	 * Bulk Request
	 * 
	 * Performs multiple requests at once, returning the results in the same order.
	 * 
	 * **Auth Requirements:** Not Authenticated
	 * */
	suspend fun bulkRequest(input: Map<String, com.lightningkite.lightningserver.typed.BulkRequest>): Map<String, com.lightningkite.lightningserver.typed.BulkResponse>
	/**
	 * Get Test Primitive
	 * 
	 * **Auth Requirements:** User with scope *
	 * */
	suspend fun getTestPrimitive(): kotlin.String

	val userEndpoints: com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.demo.User, kotlin.uuid.Uuid>

	interface UploadEarlyEndpointApi {
		/**
		 * Upload File for Request
		 * 
		 * Upload a file to make a request later.  Times out in around 10 minutes.
		 * 
		 * **Auth Requirements:** Not Authenticated
		 * */
		suspend fun uploadFileforRequest(): com.lightningkite.lightningserver.files.UploadInformation
		/**
		 * Verify uploaded file
		 * 
		 * Checks out a file and moves it out of jail if it's safe.  Makes for significantly faster subsequent requests.
		 * 
		 * **Auth Requirements:** Not Authenticated
		 * */
		suspend fun verifyuploadedfile(input: kotlin.String): kotlin.String
	}
	val uploadEarlyEndpoint: UploadEarlyEndpointApi

	val testModelEndpoints: com.lightningkite.lightningserver.typed.ClientModelRestEndpointsAndUpdatesWebsocket<com.lightningkite.lightningserver.demo.TestModel, kotlin.uuid.Uuid>

	val sms: com.lightningkite.lightningserver.sessions.proofs.ProofClientEndpoints.Sms

	val email: com.lightningkite.lightningserver.sessions.proofs.ProofClientEndpoints.Email

	interface TimeBasedOTPProof : com.lightningkite.lightningserver.sessions.proofs.ProofClientEndpoints.TimeBasedOTP, com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.sessions.TotpSecret, kotlin.uuid.Uuid> {
	}
	val totp: TimeBasedOTPProof

	interface PasswordProof : com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.sessions.PasswordSecret, kotlin.uuid.Uuid>, com.lightningkite.lightningserver.sessions.proofs.ProofClientEndpoints.Password {
	}
	val password: PasswordProof

	interface KnownDeviceProof : com.lightningkite.lightningserver.sessions.proofs.ProofClientEndpoints.KnownDevice, com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.sessions.KnownDeviceSecret, kotlin.uuid.Uuid> {
	}
	val knownDevice: KnownDeviceProof

	interface UserAuth : com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.sessions.Session<com.lightningkite.lightningserver.demo.User, kotlin.uuid.Uuid>, kotlin.uuid.Uuid>, com.lightningkite.lightningserver.sessions.proofs.AuthClientEndpoints<kotlin.uuid.Uuid> {
	}
	val userAuth: UserAuth
}
