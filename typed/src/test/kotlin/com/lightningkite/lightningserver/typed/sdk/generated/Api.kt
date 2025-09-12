package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.typed.Fetcher
import kotlinx.serialization.builtins.serializer

interface Api {
	suspend fun index(): kotlin.Int
	suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int

	interface PredefinedEndpoints {
		suspend fun foo(input: kotlin.Int): kotlin.Int
	}
	val predefinedEndpoints: PredefinedEndpoints

	interface ModuleApi : com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.typed.sdk.TestModel, kotlin.uuid.Uuid> {
		suspend fun test(first: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String
		suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int
		suspend fun inlinedEndpoint2(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int

		interface DefaultEndpoints : com.lightningkite.lightningserver.typed.ClientModelRestEndpointsAndUpdatesWebsocket<com.lightningkite.lightningserver.typed.sdk.TestModel, kotlin.uuid.Uuid> {
			suspend fun test(second: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String

			interface NotInlinedApi {
				suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int
			}
			val notInlined: NotInlinedApi
		}
		val default: DefaultEndpoints

		interface DefaultEndpoints2 : com.lightningkite.lightningserver.typed.ClientModelRestEndpointsAndUpdatesWebsocket<com.lightningkite.lightningserver.typed.sdk.TestModel, kotlin.uuid.Uuid> {
			suspend fun test(second: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String

			interface NotInlinedApi {
				suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int
			}
			val notInlined: NotInlinedApi
		}
		val default2: DefaultEndpoints2
	}
	val module: ModuleApi

	interface CustomEndpoints : com.lightningkite.lightningserver.typed.ClientModelRestEndpointsAndUpdatesWebsocket<com.lightningkite.lightningserver.typed.sdk.TestModel, kotlin.uuid.Uuid> {
		suspend fun test(second: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String

		interface NotInlinedApi {
			suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int
		}
		val notInlined: NotInlinedApi
	}
	val custom: CustomEndpoints

	interface OtherEndpoints {
		suspend fun test(third: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String
		suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int

		val rest: com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.typed.sdk.TestModel, kotlin.uuid.Uuid>

		val rest2: com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.typed.sdk.TestModel, kotlin.uuid.Uuid>
	}
	val other: OtherEndpoints
}
