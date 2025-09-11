package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.typed.Fetcher
import kotlinx.serialization.builtins.serializer
import com.lightningkite.lightningserver.typed.urlifyToCommaString

class LiveApi(val fetcher: Fetcher) : Api {
	override suspend fun index(): kotlin.Int =
		fetcher("", HttpMethod.GET, kotlin.Unit.serializer(), kotlin.Unit, kotlin.Int.serializer())
	override suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int =
		fetcher("inline/action/${id.urlifyToCommaString()}/${category.urlifyToCommaString()}", HttpMethod.POST, kotlin.Unit.serializer(), kotlin.Unit, kotlin.Int.serializer())

	inner class LiveModuleApi : Api.ModuleApi, com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.typed.sdk.TestModel, kotlin.uuid.Uuid> by com.lightningkite.lightningserver.typed.ClientModelRestEndpointsLive(fetcher, "", com.lightningkite.lightningserver.typed.sdk.TestModel.serializer(), kotlin.uuid.Uuid.serializer()) {
		override suspend fun test(first: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String =
			fetcher("endpoint/${first.urlifyToCommaString()}", HttpMethod.POST, com.lightningkite.lightningserver.typed.sdk.TestInput.serializer(), input, kotlin.String.serializer())
		override suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int =
			fetcher("inline/again/action/${id.urlifyToCommaString()}/${category.urlifyToCommaString()}", HttpMethod.POST, kotlin.Unit.serializer(), kotlin.Unit, kotlin.Int.serializer())
		override suspend fun inlinedEndpoint2(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int =
			fetcher("inline/action/${id.urlifyToCommaString()}/${category.urlifyToCommaString()}", HttpMethod.POST, kotlin.Unit.serializer(), kotlin.Unit, kotlin.Int.serializer())

		inner class LiveDefaultEndpoints : Api.ModuleApi.DefaultEndpoints {
			override suspend fun test(second: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String =
				fetcher("m1/endpoint/${second.urlifyToCommaString()}", HttpMethod.POST, com.lightningkite.lightningserver.typed.sdk.TestInput.serializer(), input, kotlin.String.serializer())

			inner class LiveNotInlinedApi : Api.ModuleApi.DefaultEndpoints.NotInlinedApi {
				override suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int =
					fetcher("m1/second/action/${id.urlifyToCommaString()}/${category.urlifyToCommaString()}", HttpMethod.POST, kotlin.Unit.serializer(), kotlin.Unit, kotlin.Int.serializer())
			}
			override val notInlined = LiveNotInlinedApi()
		}
		override val default = LiveDefaultEndpoints()

		inner class LiveDefaultEndpoints2 : Api.ModuleApi.DefaultEndpoints2 {
			override suspend fun test(second: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String =
				fetcher("m1/endpoint/${second.urlifyToCommaString()}", HttpMethod.POST, com.lightningkite.lightningserver.typed.sdk.TestInput.serializer(), input, kotlin.String.serializer())

			inner class LiveNotInlinedApi : Api.ModuleApi.DefaultEndpoints2.NotInlinedApi {
				override suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int =
					fetcher("m1/duplicate/action/${id.urlifyToCommaString()}/${category.urlifyToCommaString()}", HttpMethod.POST, kotlin.Unit.serializer(), kotlin.Unit, kotlin.Int.serializer())
			}
			override val notInlined = LiveNotInlinedApi()
		}
		override val default2 = LiveDefaultEndpoints2()
	}
	override val module = LiveModuleApi()

	inner class LiveCustomEndpoints : Api.CustomEndpoints {
		override suspend fun test(second: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String =
			fetcher("endpoint/${second.urlifyToCommaString()}", HttpMethod.POST, com.lightningkite.lightningserver.typed.sdk.TestInput.serializer(), input, kotlin.String.serializer())

		inner class LiveNotInlinedApi : Api.CustomEndpoints.NotInlinedApi {
			override suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int =
				fetcher("m2/action/${id.urlifyToCommaString()}/${category.urlifyToCommaString()}", HttpMethod.POST, kotlin.Unit.serializer(), kotlin.Unit, kotlin.Int.serializer())
		}
		override val notInlined = LiveNotInlinedApi()
	}
	override val custom = LiveCustomEndpoints()

	inner class LiveOtherEndpoints : Api.OtherEndpoints {
		override suspend fun test(third: kotlin.String, input: com.lightningkite.lightningserver.typed.sdk.TestInput): kotlin.String =
			fetcher("endpoint/${third.urlifyToCommaString()}", HttpMethod.POST, com.lightningkite.lightningserver.typed.sdk.TestInput.serializer(), input, kotlin.String.serializer())
		override suspend fun inlinedEndpoint(id: kotlin.uuid.Uuid, category: kotlin.uuid.Uuid): kotlin.Int =
			fetcher("inline/action/${id.urlifyToCommaString()}/${category.urlifyToCommaString()}", HttpMethod.POST, kotlin.Unit.serializer(), kotlin.Unit, kotlin.Int.serializer())

		override val rest = com.lightningkite.lightningserver.typed.ClientModelRestEndpointsLive(fetcher, "third", com.lightningkite.lightningserver.typed.sdk.TestModel.serializer(), kotlin.uuid.Uuid.serializer())
	}
	override val other = LiveOtherEndpoints()
}
