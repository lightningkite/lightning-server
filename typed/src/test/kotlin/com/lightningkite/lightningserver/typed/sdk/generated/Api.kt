package com.lightningkite.lightningserver.typed.sdk



interface Api {
	/**
	 * Action
	 * 
	 * Does something really really cool...
	 * 
	 * **Auth Requirements:** User *or* Not Authenticated
	 * */
	suspend fun improperSDKFunctionName(): kotlin.Int
	/**
	 * Index
	 * 
	 * **Auth Requirements:** Not Authenticated
	 * */
	suspend fun index(): kotlin.Int
	/**
	 * Inlined Endpoint
	 * 
	 * This endpoint is sometimes inlined, sometimes not.
	 * 
	 * **Auth Requirements:** IsAdmin *or* IsSuperUser (User with scope * and an additional requirement)
	 * */
	suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int

	interface PredefinedEndpoints {
		/**
		 * Pre-Defined Endpoint
		 * 
		 * This is an endpoint included through a pre-build definition
		 * 
		 * **Auth Requirements:** User with scope pre:defined *or* User with scope foo *or* IsSuperUser (User with scope * and an additional requirement)
		 * */
		suspend fun preDefinedEndpoint(input: kotlin.Int): kotlin.Int
	}
	val predefinedEndpoints: PredefinedEndpoints

	interface ModuleApi : com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.typed.sdk.TestModel, kotlin.uuid.Uuid> {
		/**
		 * Test Endpoint
		 * 
		 * This is a test endpoint for the sdk
		 * 
		 * **Auth Requirements:** Authenticated with scopes [[sdk:test, sdk:other]] and max age of 8h *or* IsSuperUser (User with scope * and an additional requirement)
		 * */
		suspend fun testSdkEndpoint(first: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String
		/**
		 * Inlined Endpoint
		 * 
		 * This endpoint is sometimes inlined, sometimes not.
		 * 
		 * **Auth Requirements:** IsAdmin *or* IsSuperUser (User with scope * and an additional requirement)
		 * */
		suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int
		/**
		 * Inlined Endpoint
		 * 
		 * This endpoint is sometimes inlined, sometimes not.
		 * 
		 * **Auth Requirements:** IsAdmin *or* IsSuperUser (User with scope * and an additional requirement)
		 * */
		suspend fun inlinedEndpoint2(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int

		interface DefaultEndpoints : com.lightningkite.lightningserver.typed.ClientModelRestEndpointsAndUpdatesWebsocket<com.lightningkite.lightningserver.typed.sdk.TestModel, kotlin.uuid.Uuid> {
			/**
			 * Test Endpoint
			 * 
			 * This is a test endpoint for the sdk
			 * 
			 * **Auth Requirements:** Authenticated with scopes [[sdk:test, sdk:other]] and max age of 8h *or* IsSuperUser (User with scope * and an additional requirement)
			 * */
			suspend fun testSdkEndpoint(second: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String

			interface NotInlinedApi {
				/**
				 * Inlined Endpoint
				 * 
				 * This endpoint is sometimes inlined, sometimes not.
				 * 
				 * **Auth Requirements:** IsAdmin *or* IsSuperUser (User with scope * and an additional requirement)
				 * */
				suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int
			}
			val notInlined: NotInlinedApi
		}
		val default: DefaultEndpoints

		interface DefaultEndpoints2 : com.lightningkite.lightningserver.typed.ClientModelRestEndpointsAndUpdatesWebsocket<com.lightningkite.lightningserver.typed.sdk.TestModel, kotlin.uuid.Uuid> {
			/**
			 * Test Endpoint
			 * 
			 * This is a test endpoint for the sdk
			 * 
			 * **Auth Requirements:** Authenticated with scopes [[sdk:test, sdk:other]] and max age of 8h *or* IsSuperUser (User with scope * and an additional requirement)
			 * */
			suspend fun testSdkEndpoint(second: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String

			interface NotInlinedApi {
				/**
				 * Inlined Endpoint
				 * 
				 * This endpoint is sometimes inlined, sometimes not.
				 * 
				 * **Auth Requirements:** IsAdmin *or* IsSuperUser (User with scope * and an additional requirement)
				 * */
				suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int
			}
			val notInlined: NotInlinedApi
		}
		val default2: DefaultEndpoints2
	}
	val module: ModuleApi

	interface CustomEndpoints : com.lightningkite.lightningserver.typed.ClientModelRestEndpointsAndUpdatesWebsocket<com.lightningkite.lightningserver.typed.sdk.TestModel, kotlin.uuid.Uuid> {
		/**
		 * Test Endpoint
		 * 
		 * This is a test endpoint for the sdk
		 * 
		 * **Auth Requirements:** Authenticated with scopes [[sdk:test, sdk:other]] and max age of 8h *or* IsSuperUser (User with scope * and an additional requirement)
		 * */
		suspend fun testSdkEndpoint(second: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String

		interface NotInlinedApi {
			/**
			 * Inlined Endpoint
			 * 
			 * This endpoint is sometimes inlined, sometimes not.
			 * 
			 * **Auth Requirements:** IsAdmin *or* IsSuperUser (User with scope * and an additional requirement)
			 * */
			suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int
		}
		val notInlined: NotInlinedApi
	}
	val custom: CustomEndpoints

	interface OtherEndpoints {
		/**
		 * Test Endpoint
		 * 
		 * This is a test endpoint for the sdk
		 * 
		 * **Auth Requirements:** Authenticated with scopes [[sdk:test, sdk:other]] and max age of 8h *or* IsSuperUser (User with scope * and an additional requirement)
		 * */
		suspend fun testSdkEndpoint(third: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String
		/**
		 * Inlined Endpoint
		 * 
		 * This endpoint is sometimes inlined, sometimes not.
		 * 
		 * **Auth Requirements:** IsAdmin *or* IsSuperUser (User with scope * and an additional requirement)
		 * */
		suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int

		val rest: com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.typed.sdk.TestModel, kotlin.uuid.Uuid>

		val rest2: com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.typed.sdk.TestModel, kotlin.uuid.Uuid>
	}
	val other: OtherEndpoints
}
