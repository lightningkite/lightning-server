@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.Index
import com.lightningkite.lightningdb.IndexSet
import com.lightningkite.lightningserver.auth.proof.WebAuthN.Registration.CreateExtensionResponse
import com.lightningkite.now
import kotlinx.datetime.Instant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi


@Serializable
@GenerateDataClassPaths
@IndexSet(["subjectType", "subjectId"])
data class WebAuthNCredential(
    override val _id: String,
    val displayName: String,
    val establishedAt: Instant = now(),
    val lastUsedAt: Instant? = null,
    @Index val subjectId: String,
    @Index val subjectType: String,
    val residentKey: Boolean,
    val authenticatorAttachment: String,
    val attestationObject: String, // Base64 url-encoded
    val transports: List<String>,
    @Index val disabledAt: Instant? = null,
) : HasId<String>


object WebAuthN {

    @OptIn(ExperimentalEncodingApi::class)
    val base64Encoder = Base64.UrlSafe

    @OptIn(ExperimentalEncodingApi::class)
    val base64Decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    @Serializable(with = PublicKeyAlgorithmSerializer::class)
    enum class PublicKeyAlgorithm(val coseAlgorithmId: Int) {
        RS256(-257),
        ES256(-7),
        EdDSA(-8),
        PS256(-37);

        companion object {
            fun fromCoseId(codeId: Int): PublicKeyAlgorithm? = entries.find { it.coseAlgorithmId == codeId }
        }
    }

    object PublicKeyAlgorithmSerializer : KSerializer<PublicKeyAlgorithm> {

        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("PublicKeyAlgorithm", PrimitiveKind.INT)

        override fun serialize(encoder: Encoder, value: PublicKeyAlgorithm) {
            encoder.encodeInt(value.coseAlgorithmId)
        }

        override fun deserialize(decoder: Decoder): PublicKeyAlgorithm {
            val coseAlgorithmId = decoder.decodeInt()
            return PublicKeyAlgorithm.entries.first { it.coseAlgorithmId == coseAlgorithmId }
        }
    }

    @Serializable
    data class PublicKeyCredentialParameters(
        val alg: PublicKeyAlgorithm,
    ) {
        @EncodeDefault(Mode.ALWAYS)
        val type: String = "public-key"
    }

    @Serializable
    enum class Attestation(val standardName: String) {
        None("none"),
        Direct("direct"),
        Enterprise("enterprise"),
        Indirect("indirect");

        companion object {
            fun fromStandardName(standardName: String): Attestation =
                entries.find { it.standardName == standardName }
                    ?: throw IllegalArgumentException("$standardName is an invalid option")
        }
    }

    @Serializable
    enum class GeneralPreference(val standardName: String) {
        Discouraged("discouraged"),
        Preferred("preferred"),
        Required("required");

        companion object {
            fun fromStandardName(standardName: String): GeneralPreference =
                entries.find { it.standardName == standardName }
                    ?: throw IllegalArgumentException("$standardName is an invalid option")
        }
    }

    @Serializable
    enum class AuthenticatorAttachment(val standardName: String) {
        Platform("platform"),
        CrossPlatform("cross-platform"),
        ;

        companion object {
            fun fromStandardName(standardName: String): AuthenticatorAttachment =
                entries.find { it.standardName == standardName }
                    ?: throw IllegalArgumentException("$standardName is an invalid option")
        }
    }

    @Serializable
    enum class Transport(val standardName: String) {
        BLE("ble"),
        Hybrid("hybrid"),
        Internal("internal"),
        NFC("nfc"),
        USB("usb");

        companion object {
            fun fromStandardName(standardName: String): Transport =
                entries.find { it.standardName == standardName }
                    ?: throw IllegalArgumentException("$standardName is an invalid option")
        }
    }

    @Serializable
    data class ExistingCredential(
        val id: String, // Base64 url-encoded
        val transports: List<Transport> = emptyList(),
    ) {
        @EncodeDefault(Mode.ALWAYS)
        val type: String = "public-key"
    }

    @Serializable
    data class AuthenticatorSelectionOptions(
        val authenticatorAttachment: AuthenticatorAttachment? = null,
        val userVerification: GeneralPreference = GeneralPreference.Preferred,
    )

    @Serializable
    enum class Hints(val standardName: String) {
        SecurityKey("security-key"),
        ClientDevice("client-device"),
        Hybrid("hybrid");

        companion object {
            fun fromStandardName(standardName: String): Hints =
                entries.find { it.standardName == standardName }
                    ?: throw IllegalArgumentException("$standardName is an invalid option")
        }
    }

    @Serializable
    data class PublicKeyCredentialUserEntity(
        val displayName: String,
        val id: String,
        val name: String,
    )

    @Serializable
    data class ClientData(
        val challenge: String,
        val origin: String,
        val crossOrigin: Boolean,
        val type: String,
    )

    object Registration {

        @Serializable
        data class RegistrationResponse(
            val challengeId: String,
            val options: PublicKeyCredentialCreationOptions,
        )

        @Serializable
        data class AuthenticatorSelection(
            val authenticatorAttachment: AuthenticatorAttachment? = null,
            val residentKey: GeneralPreference = GeneralPreference.Discouraged,
            val userVerification: GeneralPreference = GeneralPreference.Preferred,
        )


        @Serializable
        data class LargeBlob(
            val support: LargeBlobPreference,
        )

        @Serializable
        data class LargeBlobResponse(
            val supported: Boolean,
        )

        @Serializable
        data class CreateExtensions(
            val appidExclude: String? = null,
            val credProps: Boolean? = null,
            val credentialProtectionPolicy: CredentialProtectionPolicy? = null,
            val enforceCredentialProtectionPolicy: Boolean? = null,
            val largeBlob: LargeBlob? = null,
            val minPinLength: Boolean? = null,
//    val payment: = null,
        )

        @Serializable
        data class CreateExtensionResponse(
            val appidExclude: Boolean? = null,
            val credProps: CredPropsResponse? = null,
            val credProtect: Int? = null,
            val largeBlob: LargeBlobResponse? = null,
            val minPinLength: UInt? = null,
//    val payment: = null,
        )

        @Serializable
        data class CredPropsResponse(
            val rk: Boolean,
        )

        @Serializable
        enum class LargeBlobPreference(val standardName: String) {
            Preferred("preferred"),
            Required("required");

            companion object {
                fun fromStandardName(standardName: String): LargeBlobPreference =
                    entries.find { it.standardName == standardName }
                        ?: throw IllegalArgumentException("$standardName is an invalid option")
            }
        }

        enum class CredentialProtectionPolicy(val standardName: String) {
            UserVerificationOptional("userVerificationOptional"),
            UserVerificationOptionalWithCredentialIDList("userVerificationOptionalWithCredentialIDList"),
            UserVerificationRequired("userVerificationRequired");

            companion object {
                fun fromStandardName(standardName: String): CredentialProtectionPolicy =
                    entries.find { it.standardName == standardName }
                        ?: throw IllegalArgumentException("$standardName is an invalid option")
            }
        }

        //@Serializable
        //data class PaymentExtension(
        //    val isPayment: Boolean,
        //)

        @Serializable
        data class PublicKeyCredentialRpEntity(
            val id: String,
            val name: String,
        )

        data class RegistrationOptions(
            val attestation: Attestation = Attestation.None,
            val attestationFormats: List<String> = emptyList(),
            val authenticatorSelection: AuthenticatorSelectionOptions = AuthenticatorSelectionOptions(),
            val extensions: CreateExtensions = CreateExtensions(),
            val hints: List<Hints> = emptyList(),
            val pubKeyCredParams: List<PublicKeyCredentialParameters> = listOf(
                PublicKeyCredentialParameters(PublicKeyAlgorithm.EdDSA),
                PublicKeyCredentialParameters(PublicKeyAlgorithm.ES256),
                PublicKeyCredentialParameters(PublicKeyAlgorithm.RS256),
                PublicKeyCredentialParameters(PublicKeyAlgorithm.PS256),
            ),
            val user: PublicKeyCredentialUserEntity,
        )

        @Serializable
        data class PublicKeyCredentialCreationOptions(
            val attestation: Attestation = Attestation.None,
            val attestationFormats: List<String> = emptyList(),
            val authenticatorSelection: AuthenticatorSelection = AuthenticatorSelection(),
            val challenge: String, // Base64 url-encoded
            val excludeCredentials: List<ExistingCredential> = emptyList(),
            val extensions: CreateExtensions = CreateExtensions(),
            val hints: List<Hints> = emptyList(),
            val pubKeyCredParams: List<PublicKeyCredentialParameters>,
            val rp: PublicKeyCredentialRpEntity,
            val timeout: Int? = null,
            val user: PublicKeyCredentialUserEntity,
        )

        @Serializable
        data class AuthenticatorAttestationResponse(
            val attestationObject: String, // Base64 url-encoded
            val clientDataJSON: String, // Base64 Json String
            val transports: List<Transport>,
        )

        @Serializable
        data class AttestedPublicKeyCredential(
            val authenticatorAttachment: String,
            val clientExtensionResults: CreateExtensionResponse?,
            val id: String,
            val response: AuthenticatorAttestationResponse,
        ) {
            @EncodeDefault(Mode.ALWAYS)
            val type: String = "public-key"
        }

        @Serializable
        data class RegisterRequest(
            val challengeId: String,
            val displayName: String,
            val credential: AttestedPublicKeyCredential,
        )
    }

    object Authentication {

        @Serializable
        data class LargeBlob(
            val read: Boolean?,
            val write: String?, // Base64 url-encoded
        )

        @Serializable
        data class LargeBlobResponse(
            val written: Boolean,
            val blob: String?, // Base64 url-encoded
        )

        @Serializable
        data class RequestExtensions(
            val appid: String? = null,
            val largeBlob: LargeBlob? = null,
        )

        @Serializable
        data class RequestExtensionsResponse(
            val appid: Boolean? = null,
            val largeBlob: LargeBlobResponse? = null,
        )

        @Serializable
        enum class LargeBlobPreference(val standardName: String) {
            Preferred("preferred"),
            Required("required");

            companion object {
                fun fromStandardName(standardName: String): LargeBlobPreference =
                    entries.find { it.standardName == standardName }
                        ?: throw IllegalArgumentException("$standardName is an invalid option")
            }
        }

        @Serializable
        data class PublicKeyCredentialRequestOptions(
            val allowCredentials: List<ExistingCredential> = listOf(),
            val challenge: String,
            val extensions: RequestExtensions = RequestExtensions(),
            val hints: List<Hints> = emptyList(),
            val rpId: String,
            val timeout: Int? = null,
            val userVerification: GeneralPreference = GeneralPreference.Preferred,
        )

        @Serializable
        data class AssertedPublicKeyCredential(
            val id: String, // Base64 url-encoded
            val clientExtensionResults: RequestExtensionsResponse?,
            val response: AuthenticatorAssertionResponse,
        ) {
            @EncodeDefault(Mode.ALWAYS)
            val type: String = "public-key"

        }

        @Serializable
        data class AuthenticatorAssertionResponse(
            val authenticatorData: String, // Base64 url-encoded
            val clientDataJSON: String, // Base64 Json String
            val signature: String, // Base64 url-encoded
            val userHandle: String?, // Base64 url-encoded
        )

        @Serializable
        data class StartRequest(
            val subjectId: String?,
            val subjectType: String,
        )

        data class ProveOptions(
            val extensions: RequestExtensions = RequestExtensions(),
            val hints: List<Hints> = emptyList(),
            val userVerification: GeneralPreference = GeneralPreference.Preferred,
        )

        @Serializable
        data class StartResponse(
            val challengeId: String,
            val options: PublicKeyCredentialRequestOptions,
        )

        @Serializable
        data class ProveRequest(
            val challengeId: String,
            val credentials: AssertedPublicKeyCredential,
        )
    }
}
