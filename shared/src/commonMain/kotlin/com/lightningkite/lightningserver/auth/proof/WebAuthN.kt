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
val Base64.WebAuthN get() = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

@Serializable
@GenerateDataClassPaths
@IndexSet(["subjectType", "subjectId"])
data class WebAuthNCredential(
    override val _id: String,
    val subjectId: String,
    val authenticatorAttachment:String,
    val clientExtensionResults: Map<String, String>,
    val displayName: String,
    val response: AuthenticatorAttestationResponse,
    val establishedAt: Instant = now(),
    val lastUsedAt: Instant? = null,
    val expiresAt: Instant? = null,
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
/**
 * See [`PublicKeyCredentialCreationOptions`](https://developer.mozilla.org/en-US/docs/Web/API/PublicKeyCredentialCreationOptions)
 */
data class PublicKeyCredentialCreationOptions(
    val attestation: Attestation = Attestation.None,
    val attestationFormats: List<String> = emptyList(),
    @EncodeDefault(Mode.NEVER) val authenticatorSelection: AuthenticatorSelection? = null,
    val challenge: String, // base64url-encoded
    @EncodeDefault(Mode.NEVER) val excludeCredentials: List<ExistingCredential> = emptyList(),
    val extensions: Map<String, String> = emptyMap(),
    val hints: List<CreationHints> = emptyList(),
    val pubKeyCredParams: List<PublicKeyCredentialParameters>,
    val rp: PublicKeyCredentialRpEntity,
    @EncodeDefault(Mode.NEVER) val timeout: Int? = null,
    val user: PublicKeyCredentialUserEntity,
)


data class RegistrationOptions(
    val attestation: Attestation = Attestation.None,
    val attestationFormats: List<String> = emptyList(),
    val authenticatorSelection: AuthenticatorSelection? = null,
    val extensions: Map<String, String> = emptyMap(),
    val hints: List<CreationHints> = emptyList(),
    val pubKeyCredParams: List<PublicKeyCredentialParameters>,
    val user: PublicKeyCredentialUserEntity,
)

@Serializable
data class PublicKeyCredentialRequestOptions(
//    val allowCredentials: List<ExistingCredential> = listOf(),
    val challenge: String,  // base64url-encoded
    val extensions: Map<String, String> = emptyMap(),
    val hints: List<CreationHints> = emptyList(),
    @EncodeDefault(Mode.NEVER) val rpId: String? = null,
    @EncodeDefault(Mode.NEVER) val timeout: Int? = null,
    val userVerification: GeneralPreference = GeneralPreference.Preferred,
)

data class ProveOptions(
//    val allowCredentials: List<ExistingCredential> = listOf(),
    val extensions: Map<String, String> = emptyMap(),
    val hints: List<CreationHints> = emptyList(),
    val userVerification: GeneralPreference = GeneralPreference.Preferred,
)

@Serializable
data class WebAuthNStartResponse(
    val challengeId: String,
    val options: PublicKeyCredentialRequestOptions
)

@Serializable
data class WebAuthNProveRequest(
    val challengeId: String,
    val credentials: AssertedPublicKeyCredential
)

@Serializable
data class AuthenticatorSelection(
    @EncodeDefault(Mode.NEVER) val authenticatorAttachment: AuthenticatorAttachment? = null,
    val residentKey: GeneralPreference = GeneralPreference.Discouraged,
    val userVerification: GeneralPreference = GeneralPreference.Preferred,
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
    val type: String = "public-key"
}

@Serializable
data class PublicKeyCredentialParameters(
    val alg: PublicKeyAlgorithm,
) {
    val type: String = "public-key"
}

@Serializable
data class PublicKeyCredentialRpEntity(
    @EncodeDefault(Mode.NEVER) val id: String? = null,
    val name: String,
)

@Serializable
data class PublicKeyCredentialUserEntity(
    val displayName: String,
    val id: String, // base64url-encoded
    val name: String,
)

/**
 * Represents a recently created WebAuthN credential. This is the same object that is returned from
 * `CredentialContainer.create().toJSON()`
 *
 * See [`PublicKeyCredential`](https://developer.mozilla.org/en-US/docs/Web/API/PublicKeyCredential)
 */
@Serializable
data class AttestedPublicKeyCredential(
    val authenticatorAttachment:String,
    val clientExtensionResults: Map<String, String>,
    val id: String, // base64url-encoded
    val response: AuthenticatorAttestationResponse,
) {
    @EncodeDefault(Mode.ALWAYS)
    val type: String = "public-key"
}

@Serializable
data class RegisterFinishRequest(
    val displayName: String,
    val credential: AttestedPublicKeyCredential
)

@Serializable
data class AuthenticatorAttestationResponse(
    val attestationObject: String, // Base64 String
    val authenticatorData: String, // Base64 String
    val clientDataJSON: String, // Base64 String
    val publicKey: String, // Base64 String
    val publicKeyAlgorithm: Int,
    val transports: List<Transport>,
) {
    val clientData: ClientData get() = Json.decodeFromString(ClientDataJSONSerializer, clientDataJSON)
}

/**
 * Represents the result of a WebAuthN assertion response. This is the same object that is returned from
 * `CredentialContainer.get().toJSON()`
 *
 * See [`PublicKeyCredential`](https://developer.mozilla.org/en-US/docs/Web/API/PublicKeyCredential)
 */
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
    val clientDataJSON: String, // Base64 String
    val signature: String, // Base64 String
    val userHandle: String,
) {
    val clientData: ClientData get() = Json.decodeFromString(ClientDataJSONSerializer, clientDataJSON)
}

@Serializable
data class ClientData(
    val challenge: String,
    val origin: String,
    val crossOrigin: Boolean,
)

@OptIn(ExperimentalEncodingApi::class)
open class Base64JSONSerializer<T>(
    val serializationStrategy: KSerializer<T>,
    serialName: String,
) : KSerializer<T> {
    override val descriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) {
        val jsonRaw = Json.encodeToString(serializationStrategy, value).encodeToByteArray()
        encoder.encodeString(Base64.WebAuthN.encode(jsonRaw))
    }

    override fun deserialize(decoder: Decoder): T {
        val jsonRaw = Base64.WebAuthN.decode(decoder.decodeString())
        return Json.decodeFromString(serializationStrategy, jsonRaw.decodeToString())
    }
}

object ClientDataJSONSerializer : Base64JSONSerializer<ClientData>(ClientData.serializer(), "ClientData")
