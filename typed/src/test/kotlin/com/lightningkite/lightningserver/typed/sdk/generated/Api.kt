package com.lightningkite.lightningserver.typed.sdk

interface Api {
	suspend fun index(): kotlin.Int
	suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int

	interface ModuleApi: com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.typed.sdk.TestModel, kotlin.uuid.Uuid> {
		suspend fun test(first: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String
		suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int
		suspend fun inlinedEndpoint2(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int

		interface SecondModuleApi {
			suspend fun test(second: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String

			interface NotInlinedApi {
				suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int
			}
			val notInlined: NotInlinedApi
		}
		val secondModule: SecondModuleApi

		interface SecondModuleApi2 {
			suspend fun test(second: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String

			interface NotInlinedApi {
				suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int
			}
			val notInlined: NotInlinedApi
		}
		val secondModule2: SecondModuleApi
	}
	val module: ModuleApi

	interface SecondModuleApi {
		suspend fun test(second: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String

		interface NotInlinedApi {
			suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int
		}
		val notInlined: NotInlinedApi
	}
	val secondModule: SecondModuleApi

	interface ThirdModuleApi {
		suspend fun test(third: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String
		suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int
		val rest: com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.typed.sdk.TestModel, kotlin.uuid.Uuid>
	}
	val thirdModule: ThirdModuleApi
}
