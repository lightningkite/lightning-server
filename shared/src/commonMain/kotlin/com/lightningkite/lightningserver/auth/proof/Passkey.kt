package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.IndexSet
import com.lightningkite.now
import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
@GenerateDataClassPaths
@IndexSet(["subjectType", "subjectId"])
data class PasskeyCredential(
    /**
     * A globally unique id that is generated and returned by the client authenticator
     */
    override val _id: String,
    val subjectName: String,
    /**
     * The String-representation of a subject id that is also passed to the client authenticator
     * as a user id
     * (see [`PublicKeyCredentialCreationOptions.user.id`](https://developer.mozilla.org/en-US/docs/Web/API/PublicKeyCredentialCreationOptions#id_3))
     */
    val subjectId: String,
    /**
     * A friendly display name that may be set to allow users to distinguish between passkeys in cases where
     * several may have been set for a single subject (the client authenticator is unaware of this field)
     */
    val friendlyName: String? = null,

    /**
     * The DER public key of the key pair used by the client authenticator to sign challenges
     * (see [`AuthenticatorAttestationResponse.getPublicKey()`](https://developer.mozilla.org/en-US/docs/Web/API/AuthenticatorAttestationResponse/getPublicKey)
     */
    val publicKeyDerBase64: String,
    /**
     * The cryptographic algorithm used for the passkey
     * (see [`AuthenticatorAttestationResponse.getPublicKeyAlgorithm()`](https://developer.mozilla.org/en-US/docs/Web/API/AuthenticatorAttestationResponse/getPublicKeyAlgorithm)
     */
    val algorithm: PublicKeyAlgorithm,

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
    PS256(-37)
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
// related to passkeys as described here: https://developer.mozilla.org/en-US/docs/Web/API/Web_Authentication_API#creating_a_key_pair_and_registering_a_user

@Serializable
/**
 * See [`PublicKeyCredentialCreationOptions`](https://developer.mozilla.org/en-US/docs/Web/API/PublicKeyCredentialCreationOptions)
 */
data class PublicKeyCredentialCreationOptions(
    val authenticatorSelection: AuthenticatorSelection? = null,
    val challenge: String, // base64url-encoded
    val excludeCredentials: List<ExistingCredential>? = emptyList(),
    val pubKeyCredParams: List<PublicKeyCredentialParameters>? = emptyList(),
    val rp: PublicKeyCredentialRpEntity,
    val user: PublicKeyCredentialUserEntity,
)

@Serializable
data class PublicKeyCredentialRequestOptions(
    val allowCredentials: List<ExistingCredential>? = listOf(),
    val challenge: String,
)

@Serializable
data class AuthenticatorSelection(
    val userVerification: UserVerification? = UserVerification.Preferred,
)

@Serializable
enum class UserVerification {
    @SerialName("discouraged") Discouraged,
    @SerialName("preferred") Preferred,
    @SerialName("required") Required,
}

@Serializable
data class ExistingCredential(
    val id: String, // base64url-encoded
    val type: String = "public-key",
)

@Serializable
data class PublicKeyCredentialParameters(
    val alg: PublicKeyAlgorithm,
    val type: String = "public-key",
)

@Serializable
data class PublicKeyCredentialRpEntity(
    val id: String? = null,
    val name: String,
)

@Serializable
data class PublicKeyCredentialUserEntity(
    val displayName: String,
    val id: String, // base64url-encoded
    val name: String,
)

/**
 * Represents a recently created passkey. This is the same object that is returned from
 * `CredentialContainer.create().toJSON()`
 *
 * See [`PublicKeyCredential`](https://developer.mozilla.org/en-US/docs/Web/API/PublicKeyCredential)
 */
@Serializable
data class AttestedPublicKeyCredential(
    val id: String, // base64url-encoded
    val response: AuthenticatorAttestationResponse,
    val type: String = "public-key",
)

@Serializable
data class AuthenticatorAttestationResponse(
    val clientDataJSON: String,
    val publicKey: String,
    val publicKeyAlgorithm: PublicKeyAlgorithm,
) {
    val clientData: ClientData get() = Json.decodeFromString(ClientDataJSONSerializer, clientDataJSON)
}

/**
 * Represents the result of a passkey assertion response. This is the same object that is returned from
 * `CredentialContainer.get().toJSON()`
 *
 * See [`PublicKeyCredential`](https://developer.mozilla.org/en-US/docs/Web/API/PublicKeyCredential)
 */
@Serializable
data class AssertedPublicKeyCredential(
    val id: String, // base64url-encoded
    val response: AuthenticatorAssertionResponse,
    val type: String = "public-key",
)

@Serializable
data class AuthenticatorAssertionResponse(
    val authenticatorData: String,
    val clientDataJSON: String,
    val signature: String,
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
    serialName: String
) : KSerializer<T> {
    override val descriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) {
        val jsonRaw = Json.encodeToString(serializationStrategy, value).encodeToByteArray()
        encoder.encodeString(Base64.UrlSafe.encode(jsonRaw))
    }

    override fun deserialize(decoder: Decoder): T {
        val jsonRaw = Base64.UrlSafe.decode(decoder.decodeString())
        return Json.decodeFromString(serializationStrategy, jsonRaw.decodeToString())
    }
}

object ClientDataJSONSerializer : Base64JSONSerializer<ClientData>(ClientData.serializer(), "ClientData")
