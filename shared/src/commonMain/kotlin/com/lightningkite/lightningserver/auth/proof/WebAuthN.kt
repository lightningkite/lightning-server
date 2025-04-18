@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.IndexSet
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
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
val Base64.WebAuthNEncoder get() = Base64.UrlSafe
@OptIn(ExperimentalEncodingApi::class)
val Base64.WebAuthNDecoder get() = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

@Serializable
@GenerateDataClassPaths
@IndexSet(["subjectType", "subjectId"])
data class WebAuthNCredential(
    override val _id: String,
    val displayName: String,
    val establishedAt: Instant = now(),
    val lastUsedAt: Instant? = null,
    val subjectId: String,
    val subjectType: String,
    val authenticatorAttachment:String,
    val clientExtensionResults: Map<String, String>,
    val attestationObject: String, // Base64 String
    val authenticatorData: String, // Base64 String
    val clientDataJSON: String,  // Base64 Json String
    val publicKey: String, // Base64 String
    val publicKeyAlgorithm: Int,
    val transports: List<Transport>,
    val disabledAt: Instant? = null,
) : HasId<String>


@Serializable(with = PublicKeyAlgorithmSerializer::class)
enum class PublicKeyAlgorithm(val coseAlgorithmId: Int) {
    RS256(-257),
    ES256(-7),
    EdDSA(-8),
    PS256(-37);

    companion object{
        fun fromCoseId(codeId: Int): PublicKeyAlgorithm? = entries.find { it.coseAlgorithmId == codeId }
    }
}

class PublicKeyAlgorithmSerializer : KSerializer<PublicKeyAlgorithm> {

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("PublicKeyAlgorithm", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: PublicKeyAlgorithm) {
        encoder.encodeInt(value.coseAlgorithmId)
    }

    override fun deserialize(decoder: Decoder): PublicKeyAlgorithm {
        val coseAlgorithmId = decoder.decodeInt()
        return PublicKeyAlgorithm.entries.first { it.coseAlgorithmId == coseAlgorithmId }
    }
}

// We adopt the recommended approach and relevant models for client-server communication
// related to WebAuthN as described here: https://developer.mozilla.org/en-US/docs/Web/API/Web_Authentication_API#creating_a_key_pair_and_registering_a_user

@Serializable
enum class Attestation(val jsonName: String) {
    None("none"),
    Direct("direct"),
    Enterprise("enterprise"),
    Indirect("indirect"),
}

@Serializable
enum class CreationHints(val jsonName: String) {
    SecurityKey("security-key"),
    ClientDevice("client-device"),
    Hybrid("hybrid"),
}

@Serializable
data class WebAuthNRegistrationResponse(
    val challengeId: String,
    val options: PublicKeyCredentialCreationOptions
)

@Serializable
data class AuthenticatorSelection(
    val authenticatorAttachment: AuthenticatorAttachment? = null,
    val residentKey: GeneralPreference = GeneralPreference.Discouraged,
    val userVerification: GeneralPreference = GeneralPreference.Preferred,
)

@Serializable
data class PublicKeyCredentialCreationOptions(
    val attestation: Attestation = Attestation.None,
    val attestationFormats: List<String> = emptyList(),
    val authenticatorSelection: AuthenticatorSelection = AuthenticatorSelection(),
    val challenge: String, // base64url-encoded
    val excludeCredentials: List<ExistingCredential> = emptyList(),
    val extensions: Map<String, String> = emptyMap(),
    val hints: List<CreationHints> = emptyList(),
    val pubKeyCredParams: List<PublicKeyCredentialParameters>,
    val rp: PublicKeyCredentialRpEntity,
    val timeout: Int? = null,
    val user: PublicKeyCredentialUserEntity,
)

data class WebAuthNRegistrationOptions(
    val attestation: Attestation = Attestation.None,
    val attestationFormats: List<String> = emptyList(),
    val authenticatorSelection: WebAuthNAuthenticatorSelectionOptions = WebAuthNAuthenticatorSelectionOptions(),
    val extensions: Map<String, String> = emptyMap(),
    val hints: List<CreationHints> = emptyList(),
    val pubKeyCredParams: List<PublicKeyCredentialParameters>,
    val user: PublicKeyCredentialUserEntity,
)

@Serializable
data class WebAuthNAuthenticatorSelectionOptions(
    val authenticatorAttachment: AuthenticatorAttachment? = null,
    val userVerification: GeneralPreference = GeneralPreference.Preferred,
)

@Serializable
data class PublicKeyCredentialRequestOptions(
    val allowCredentials: List<ExistingCredential> = listOf(),
    val challenge: String,
    val extensions: Map<String, String> = emptyMap(),
    val hints: List<CreationHints> = emptyList(),
    val rpId: String? = null,
    val timeout: Int? = null,
    val userVerification: GeneralPreference = GeneralPreference.Preferred,
)

@Serializable
data class AssertedPublicKeyCredential(
    val id: String, // base64url-encoded
    val response: AuthenticatorAssertionResponse,
) {
    @EncodeDefault(Mode.ALWAYS)
    val type: String = "public-key"

}

@Serializable
data class AuthenticatorAssertionResponse(
    val authenticatorData: String, // Base64 String
    val clientDataJSON: String, // Base64 Json String
    val signature: String, // Base64 String
    val userHandle: String?, // Base64 String
)

@Serializable
enum class GeneralPreference(val jsonName: String) {
    Discouraged("discouraged"),
    Preferred("preferred"),
    Required("required"),
}

@Serializable
enum class AuthenticatorAttachment(val jsonName: String) {
    Platform("platform"),
    CrossPlatform("cross-platform"),
}

@Serializable
enum class Transport(val jsonName: String) {
    BLE("ble"),
    Hybrid("hybrid"),
    Internal("internal"),
    NFC("nfc"),
    USB("usb"),
}

@Serializable
data class ExistingCredential(
    val id: String, // base64url-encoded
    val transports: List<Transport> = emptyList(),
) {
    @EncodeDefault(Mode.ALWAYS)
    val type: String = "public-key"
}

@Serializable
data class PublicKeyCredentialParameters(
    val alg: PublicKeyAlgorithm,
) {
    @EncodeDefault(Mode.ALWAYS)
    val type: String = "public-key"
}

@Serializable
data class PublicKeyCredentialRpEntity(
    val id: String? = null,
    val name: String,
)

@Serializable
data class PublicKeyCredentialUserEntity(
    val displayName: String,
    val id: String,
    val name: String,
)

@Serializable
data class AttestedPublicKeyCredential(
    val authenticatorAttachment:String,
    val clientExtensionResults: Map<String, String>,
    val id: String,
    val response: AuthenticatorAttestationResponse,
) {
    @EncodeDefault(Mode.ALWAYS)
    val type: String = "public-key"
}

@Serializable
data class AuthenticatorAttestationResponse(
    val attestationObject: String, // Base64 String
    val authenticatorData: String, // Base64 String
    val clientDataJSON: String, // Base64 Json String
    val publicKey: String, // Base64 String
    val publicKeyAlgorithm: Int,
    val transports: List<Transport>,
)

data class WebAuthNProveOptions(
    val extensions: Map<String, String> = emptyMap(),
    val hints: List<CreationHints> = emptyList(),
    val userVerification: GeneralPreference = GeneralPreference.Preferred,
)

@Serializable
data class WebAuthNStart(
    val subjectId: String?,
    val subjectType: String?,
)

@Serializable
data class WebAuthNStartResponse(
    val challengeId: String,
    val options: PublicKeyCredentialRequestOptions
)

@Serializable
data class WebAuthNProve(
    val challengeId: String,
    val credentials: AssertedPublicKeyCredential
)

@Serializable
data class WebAuthNRegisterFinish(
    val challengeId: String,
    val displayName: String,
    val credential: AttestedPublicKeyCredential
)

@Serializable
data class ClientData(
    val challenge: String,
    val origin: String,
    val crossOrigin: Boolean,
    val type: String,
)
