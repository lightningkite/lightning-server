package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.typed.Fetcher
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.ListSerializer

class LiveApi(val fetcher: Fetcher) : Api {
	override suspend fun getTestObject(): com.lightningkite.lightningserver.demo.TestModel =
		fetcher("test-object", HttpMethod.GET, kotlin.Unit.serializer(), kotlin.Unit, com.lightningkite.lightningserver.demo.TestModel.serializer())
	override suspend fun getServerHealth(): com.lightningkite.lightningserver.typed.ServerHealth =
		fetcher("meta/health", HttpMethod.GET, kotlin.Unit.serializer(), kotlin.Unit, com.lightningkite.lightningserver.typed.ServerHealth.serializer())
	override suspend fun bulkRequest(input: Map<String, com.lightningkite.lightningserver.typed.BulkRequest>): Map<String, com.lightningkite.lightningserver.typed.BulkResponse> =
		fetcher("meta/bulk", HttpMethod.POST, MapSerializer(String.serializer(), com.lightningkite.lightningserver.typed.BulkRequest.serializer()), input, MapSerializer(String.serializer(), com.lightningkite.lightningserver.typed.BulkResponse.serializer()))
	override suspend fun getTestPrimitive(): kotlin.String =
		fetcher("test-primitive", HttpMethod.GET, kotlin.Unit.serializer(), kotlin.Unit, kotlin.String.serializer())

	override val userEndpoints = com.lightningkite.lightningserver.typed.LiveClientModelRestEndpoints(fetcher, "user", com.lightningkite.lightningserver.demo.User.serializer(), kotlin.uuid.Uuid.serializer())

	inner class LiveUploadEarlyEndpointApi : Api.UploadEarlyEndpointApi {
		override suspend fun uploadFileforRequest(): com.lightningkite.lightningserver.files.UploadInformation =
			fetcher("upload", HttpMethod.GET, kotlin.Unit.serializer(), kotlin.Unit, com.lightningkite.lightningserver.files.UploadInformation.serializer())
		override suspend fun verifyuploadedfile(input: kotlin.String): kotlin.String =
			fetcher("upload/verify", HttpMethod.POST, kotlin.String.serializer(), input, kotlin.String.serializer())
	}
	override val uploadEarlyEndpoint = LiveUploadEarlyEndpointApi()

	override val testModelEndpoints = com.lightningkite.lightningserver.typed.LiveClientModelRestEndpointsAndUpdatesWebsocket(fetcher, "test-model", com.lightningkite.lightningserver.demo.TestModel.serializer(), kotlin.uuid.Uuid.serializer())

	override val sms = com.lightningkite.lightningserver.sessions.proofs.LiveProofClientEndpoints.Sms(fetcher, "proof/phone", )

	override val email = com.lightningkite.lightningserver.sessions.proofs.LiveProofClientEndpoints.Email(fetcher, "proof/email", )

	inner class LiveTimeBasedOTPProof : Api.TimeBasedOTPProof, com.lightningkite.lightningserver.sessions.proofs.ProofClientEndpoints.TimeBasedOTP by com.lightningkite.lightningserver.sessions.proofs.LiveProofClientEndpoints.TimeBasedOTP(fetcher, "proof/otp", ), com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.sessions.TotpSecret, kotlin.uuid.Uuid> by com.lightningkite.lightningserver.typed.LiveClientModelRestEndpoints(fetcher, "proof/otp", com.lightningkite.lightningserver.sessions.TotpSecret.serializer(), kotlin.uuid.Uuid.serializer()) {
	}
	override val totp = LiveTimeBasedOTPProof()

	inner class LivePasswordProof : Api.PasswordProof, com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.sessions.PasswordSecret, kotlin.uuid.Uuid> by com.lightningkite.lightningserver.typed.LiveClientModelRestEndpoints(fetcher, "proof/password", com.lightningkite.lightningserver.sessions.PasswordSecret.serializer(), kotlin.uuid.Uuid.serializer()), com.lightningkite.lightningserver.sessions.proofs.ProofClientEndpoints.Password by com.lightningkite.lightningserver.sessions.proofs.LiveProofClientEndpoints.Password(fetcher, "proof/password", ) {
	}
	override val password = LivePasswordProof()

	inner class LiveKnownDeviceProof : Api.KnownDeviceProof, com.lightningkite.lightningserver.sessions.proofs.ProofClientEndpoints.KnownDevice by com.lightningkite.lightningserver.sessions.proofs.LiveProofClientEndpoints.KnownDevice(fetcher, "proof/devices", ), com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.sessions.KnownDeviceSecret, kotlin.uuid.Uuid> by com.lightningkite.lightningserver.typed.LiveClientModelRestEndpoints(fetcher, "proof/devices", com.lightningkite.lightningserver.sessions.KnownDeviceSecret.serializer(), kotlin.uuid.Uuid.serializer()) {
	}
	override val knownDevice = LiveKnownDeviceProof()

	inner class LiveUserAuth : Api.UserAuth, com.lightningkite.lightningserver.typed.ClientModelRestEndpoints<com.lightningkite.lightningserver.sessions.Session<com.lightningkite.lightningserver.demo.User, kotlin.uuid.Uuid>, kotlin.uuid.Uuid> by com.lightningkite.lightningserver.typed.LiveClientModelRestEndpoints(fetcher, "auth", com.lightningkite.lightningserver.sessions.Session.serializer(com.lightningkite.lightningserver.demo.User.serializer(), kotlin.uuid.Uuid.serializer()), kotlin.uuid.Uuid.serializer()), com.lightningkite.lightningserver.sessions.proofs.AuthClientEndpoints<kotlin.uuid.Uuid> by com.lightningkite.lightningserver.sessions.proofs.LiveAuthClientEndpoints(fetcher, "auth", kotlin.uuid.Uuid.serializer()) {
	}
	override val userAuth = LiveUserAuth()
}
