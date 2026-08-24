package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.typed.Fetcher
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.builtins.*
import kotlinx.serialization.ExperimentalSerializationApi

@OptIn(ExperimentalSerializationApi::class)
class LiveApi(val fetcher: Fetcher) : Api {
	override fun withHeaderCalculator(calculator: suspend () -> List<Pair<String, String>>): LiveApi = 
		LiveApi(fetcher.withHeaderCalculator(calculator))
	override suspend fun index(): kotlin.Int =
		fetcher("", HttpMethod.GET, kotlin.Unit.serializer(), kotlin.Unit, kotlin.Int.serializer())
	override suspend fun improperSDKFunctionName(): kotlin.Int =
		fetcher("", HttpMethod.POST, kotlin.Unit.serializer(), kotlin.Unit, kotlin.Int.serializer())
	override suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int =
		fetcher("inline/action/${fetcher.url(id, kotlin.uuid.Uuid.serializer())}/${fetcher.url(category, kotlin.uuid.Uuid.serializer())}", HttpMethod.POST, kotlin.Unit.serializer(), kotlin.Unit, kotlin.Int.serializer())

	inner class LivePredefinedEndpoints : Api.PredefinedEndpoints {
		override suspend fun preDefinedEndpoint(input: kotlin.Int): kotlin.Int =
			fetcher("predefined/foo", HttpMethod.POST, kotlin.Int.serializer(), input, kotlin.Int.serializer())
	}
	override val predefinedEndpoints = LivePredefinedEndpoints()

	inner class LiveModuleApi : Api.ModuleApi, com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.typed.sdk.TestModel, com.lightningkite.lightningserver.typed.sdk.TestModel.ID> by com.lightningkite.lightningserver.typed.LiveClientModelRestEndpoints(fetcher, "m1/rest", com.lightningkite.lightningserver.typed.sdk.TestModel.serializer(), com.lightningkite.lightningserver.typed.sdk.TestModel.ID.serializer()) {
		override suspend fun testSdkEndpoint(first: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String =
			fetcher("m1/endpoint/${fetcher.url(first, kotlin.String.serializer())}", HttpMethod.POST, com.lightningkite.lightningserver.typed.sdk.TestInput.serializer(), input, kotlin.String.serializer())
		override suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int =
			fetcher("m1/inline/again/action/${fetcher.url(id, kotlin.uuid.Uuid.serializer())}/${fetcher.url(category, kotlin.uuid.Uuid.serializer())}", HttpMethod.POST, kotlin.Unit.serializer(), kotlin.Unit, kotlin.Int.serializer())
		override suspend fun inlinedEndpoint2(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int =
			fetcher("m1/inline/action/${fetcher.url(id, kotlin.uuid.Uuid.serializer())}/${fetcher.url(category, kotlin.uuid.Uuid.serializer())}", HttpMethod.POST, kotlin.Unit.serializer(), kotlin.Unit, kotlin.Int.serializer())

		inner class LiveDefaultEndpoints : Api.ModuleApi.DefaultEndpoints, com.lightningkite.lightningserver.typed.ClientModelRestEndpointsAndUpdatesWebSocket<com.lightningkite.lightningserver.typed.sdk.TestModel, com.lightningkite.lightningserver.typed.sdk.TestModel.ID> by com.lightningkite.lightningserver.typed.LiveClientModelRestEndpointsAndUpdatesWebSocket(fetcher, "m1/second/rest", com.lightningkite.lightningserver.typed.sdk.TestModel.serializer(), com.lightningkite.lightningserver.typed.sdk.TestModel.ID.serializer()) {
			override suspend fun testSdkEndpoint(second: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String =
				fetcher("m1/second/endpoint/${fetcher.url(second, kotlin.String.serializer())}", HttpMethod.POST, com.lightningkite.lightningserver.typed.sdk.TestInput.serializer(), input, kotlin.String.serializer())

			inner class LiveNotInlinedApi : Api.ModuleApi.DefaultEndpoints.NotInlinedApi {
				override suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int =
					fetcher("m1/second/noinline/action/${fetcher.url(id, kotlin.uuid.Uuid.serializer())}/${fetcher.url(category, kotlin.uuid.Uuid.serializer())}", HttpMethod.POST, kotlin.Unit.serializer(), kotlin.Unit, kotlin.Int.serializer())
			}
			override val notInlined = LiveNotInlinedApi()
		}
		override val default = LiveDefaultEndpoints()

		inner class LiveDefaultEndpoints2 : Api.ModuleApi.DefaultEndpoints2, com.lightningkite.lightningserver.typed.ClientModelRestEndpointsAndUpdatesWebSocket<com.lightningkite.lightningserver.typed.sdk.TestModel, com.lightningkite.lightningserver.typed.sdk.TestModel.ID> by com.lightningkite.lightningserver.typed.LiveClientModelRestEndpointsAndUpdatesWebSocket(fetcher, "m1/duplicate/rest", com.lightningkite.lightningserver.typed.sdk.TestModel.serializer(), com.lightningkite.lightningserver.typed.sdk.TestModel.ID.serializer()) {
			override suspend fun testSdkEndpoint(second: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String =
				fetcher("m1/duplicate/endpoint/${fetcher.url(second, kotlin.String.serializer())}", HttpMethod.POST, com.lightningkite.lightningserver.typed.sdk.TestInput.serializer(), input, kotlin.String.serializer())

			inner class LiveNotInlinedApi : Api.ModuleApi.DefaultEndpoints2.NotInlinedApi {
				override suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int =
					fetcher("m1/duplicate/noinline/action/${fetcher.url(id, kotlin.uuid.Uuid.serializer())}/${fetcher.url(category, kotlin.uuid.Uuid.serializer())}", HttpMethod.POST, kotlin.Unit.serializer(), kotlin.Unit, kotlin.Int.serializer())
			}
			override val notInlined = LiveNotInlinedApi()
		}
		override val default2 = LiveDefaultEndpoints2()
	}
	override val module = LiveModuleApi()

	inner class LiveCustomEndpoints : Api.CustomEndpoints, com.lightningkite.lightningserver.typed.ClientModelRestEndpointsAndUpdatesWebSocket<com.lightningkite.lightningserver.typed.sdk.TestModel, com.lightningkite.lightningserver.typed.sdk.TestModel.ID> by com.lightningkite.lightningserver.typed.LiveClientModelRestEndpointsAndUpdatesWebSocket(fetcher, "m2/rest", com.lightningkite.lightningserver.typed.sdk.TestModel.serializer(), com.lightningkite.lightningserver.typed.sdk.TestModel.ID.serializer()) {
		override suspend fun testSdkEndpoint(second: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String =
			fetcher("m2/endpoint/${fetcher.url(second, kotlin.String.serializer())}", HttpMethod.POST, com.lightningkite.lightningserver.typed.sdk.TestInput.serializer(), input, kotlin.String.serializer())

		inner class LiveNotInlinedApi : Api.CustomEndpoints.NotInlinedApi {
			override suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int =
				fetcher("m2/noinline/action/${fetcher.url(id, kotlin.uuid.Uuid.serializer())}/${fetcher.url(category, kotlin.uuid.Uuid.serializer())}", HttpMethod.POST, kotlin.Unit.serializer(), kotlin.Unit, kotlin.Int.serializer())
		}
		override val notInlined = LiveNotInlinedApi()
	}
	override val custom = LiveCustomEndpoints()

	inner class LiveOtherEndpoints : Api.OtherEndpoints {
		override suspend fun testSdkEndpoint(third: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String =
			fetcher("third/endpoint/${fetcher.url(third, kotlin.String.serializer())}", HttpMethod.POST, com.lightningkite.lightningserver.typed.sdk.TestInput.serializer(), input, kotlin.String.serializer())
		override suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int =
			fetcher("third/inline/action/${fetcher.url(id, kotlin.uuid.Uuid.serializer())}/${fetcher.url(category, kotlin.uuid.Uuid.serializer())}", HttpMethod.POST, kotlin.Unit.serializer(), kotlin.Unit, kotlin.Int.serializer())

		override val rest = com.lightningkite.lightningserver.typed.LiveClientModelRestEndpoints(fetcher, "third/rest", com.lightningkite.lightningserver.typed.sdk.TestModel.serializer(), com.lightningkite.lightningserver.typed.sdk.TestModel.ID.serializer())

		override val rest2 = com.lightningkite.lightningserver.typed.LiveClientModelRestEndpoints(fetcher, "third/rest2", com.lightningkite.lightningserver.typed.sdk.TestModel.serializer(), com.lightningkite.lightningserver.typed.sdk.TestModel.ID.serializer())
	}
	override val other = LiveOtherEndpoints()
}
