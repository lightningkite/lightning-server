@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.UseContextualSerialization
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi


/**
 * Stored credential for WebAuthn (Web Authentication) authentication.
 * WebAuthn enables passwordless authentication using hardware security keys, biometrics, or platform authenticators.
 *
 * @property _id Credential ID (Base64 url-encoded), serves as unique identifier
 * @property subjectId ID of the user this credential belongs to
 * @property subjectType Type of subject (e.g., "User")
 * @property displayName Human-readable name for this credential (e.g., "YubiKey 5C", "Touch ID")
 * @property residentKey Whether this is a resident key (stored on the authenticator)
 * @property authenticatorAttachment Type of authenticator ("platform" for built-in, "cross-platform" for external)
 * @property attestationObject Base64 url-encoded attestation object containing public key and metadata
 * @property lastSignCount Counter from last authentication (used to detect cloned authenticators)
 * @property transports Available transport methods (USB, NFC, BLE, internal, hybrid)
 * @property establishedAt When this credential was registered
 * @property lastUsedAt Last time this credential was used for authentication
 * @property expiresAt Optional expiration time for the credential
 * @property disabledAt If set, this credential has been disabled
 *
 * Security: The sign count should increment on each use. A decrease indicates credential cloning.
 */
@Serializable
@GenerateDataClassPaths
@IndexSet(["subjectId", "subjectType", "expiresAt", "disabledAt"])
public data class WebAuthNCredential(
    override val _id: String,
    @Index val subjectId: String,
    @Index val subjectType: String,
    val displayName: String,

    val residentKey: Boolean,
    val authenticatorAttachment: String,
    val attestationObject: String, // Base64 url-encoded
    val lastSignCount: Long,
    val transports: List<String>,

    val establishedAt: Instant,
    val lastUsedAt: Instant? = null,
    val expiresAt: Instant? = null,
    @Index val disabledAt: Instant? = null,
) : HasId<String>


/**
 * WebAuthn (Web Authentication API) models and utilities.
 * Implements W3C WebAuthn specification for passwordless authentication using public key cryptography.
 *
 * WebAuthn allows users to authenticate using:
 * - Hardware security keys (YubiKey, Titan)
 * - Platform authenticators (Touch ID, Face ID, Windows Hello)
 * - Cross-platform authenticators (USB, NFC, BLE devices)
 */
public object WebAuthN {

    /**
     * Base64 URL-safe encoder for WebAuthn data.
     * Used for encoding binary data (challenges, credentials, signatures) for transmission.
     */
    @OptIn(ExperimentalEncodingApi::class)
    public val base64Encoder: Base64 = Base64.UrlSafe

    /**
     * Base64 URL-safe decoder with optional padding.
     * Accepts both padded and unpadded Base64 strings per WebAuthn specification.
     */
    @OptIn(ExperimentalEncodingApi::class)
    public val base64Decoder: Base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    @Serializable(with = PublicKeyAlgorithmSerializer::class)
    public enum class PublicKeyAlgorithm(public val coseAlgorithmId: Int) {
        RS256(-257),
        ES256(-7),
        EdDSA(-8),
        PS256(-37);

        public companion object {
            public fun fromCoseId(codeId: Int): PublicKeyAlgorithm? = entries.find { it.coseAlgorithmId == codeId }
        }
    }

    public object PublicKeyAlgorithmSerializer : KSerializer<PublicKeyAlgorithm> {

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
    public data class PublicKeyCredentialParameters(
        val alg: PublicKeyAlgorithm,
    ) {
        @EncodeDefault(Mode.ALWAYS)
        val type: String = "public-key"
    }

    @Serializable
    public enum class Attestation(public val standardName: String) {
        None("none"),
        Direct("direct"),
        Enterprise("enterprise"),
        Indirect("indirect");

        public companion object {
            public fun fromStandardName(standardName: String): Attestation =
                entries.find { it.standardName == standardName }
                    ?: throw IllegalArgumentException("$standardName is an invalid option")
        }
    }

    @Serializable
    public enum class GeneralPreference(public val standardName: String) {
        Discouraged("discouraged"),
        Preferred("preferred"),
        Required("required");

        public companion object {
            public fun fromStandardName(standardName: String): GeneralPreference =
                entries.find { it.standardName == standardName }
                    ?: throw IllegalArgumentException("$standardName is an invalid option")
        }
    }

    @Serializable
    public enum class AuthenticatorAttachment(public val standardName: String) {
        Platform("platform"),
        CrossPlatform("cross-platform"),
        ;

        public companion object {
            public fun fromStandardName(standardName: String): AuthenticatorAttachment =
                entries.find { it.standardName == standardName }
                    ?: throw IllegalArgumentException("$standardName is an invalid option")
        }
    }

    @Serializable
    public enum class Transport(public val standardName: String) {
        BLE("ble"),
        Hybrid("hybrid"),
        Internal("internal"),
        NFC("nfc"),
        USB("usb");

        public companion object {
            public fun fromStandardName(standardName: String): Transport =
                entries.find { it.standardName == standardName }
                    ?: throw IllegalArgumentException("$standardName is an invalid option")
        }
    }

    @Serializable
    public data class ExistingCredential(
        public val id: String, // Base64 url-encoded
        public val transports: List<Transport> = emptyList(),
    ) {
        @EncodeDefault(Mode.ALWAYS)
        public val type: String = "public-key"
    }

    @Serializable
    public data class AuthenticatorSelectionOptions(
        public val authenticatorAttachment: AuthenticatorAttachment? = null,
        public val userVerification: GeneralPreference = GeneralPreference.Preferred,
    )

    @Serializable
    public enum class Hints(public val standardName: String) {
        SecurityKey("security-key"),
        ClientDevice("client-device"),
        Hybrid("hybrid");

        public companion object {
            public fun fromStandardName(standardName: String): Hints =
                entries.find { it.standardName == standardName }
                    ?: throw IllegalArgumentException("$standardName is an invalid option")
        }
    }

    @Serializable
    public data class PublicKeyCredentialUserEntity(
        public val displayName: String,
        public val id: String,
        public val name: String,
    )

    @Serializable
    public data class ClientData(
        public val challenge: String,
        public val origin: String,
        public val crossOrigin: Boolean,
        public val type: String,
    )

    public object Registration {

        @Serializable
        public data class RegistrationResponse(
            public val challengeId: String,
            public val options: PublicKeyCredentialCreationOptions,
        )

        @Serializable
        public data class AuthenticatorSelection(
            public val authenticatorAttachment: AuthenticatorAttachment? = null,
            public val residentKey: GeneralPreference = GeneralPreference.Discouraged,
            public val userVerification: GeneralPreference = GeneralPreference.Preferred,
        )


        @Serializable
        public data class LargeBlob(
            public val support: LargeBlobPreference,
        )

        @Serializable
        public data class LargeBlobResponse(
            public val supported: Boolean,
        )

        @Serializable
        public data class CreateExtensions(
            public val appidExclude: String? = null,
            public val credProps: Boolean? = null,
            public val credentialProtectionPolicy: CredentialProtectionPolicy? = null,
            public val enforceCredentialProtectionPolicy: Boolean? = null,
            public val largeBlob: LargeBlob? = null,
            public val minPinLength: Boolean? = null,
//    public val payment: = null,
        )

        @Serializable
        public data class CreateExtensionResponse(
            public val appidExclude: Boolean? = null,
            public val credProps: CredPropsResponse? = null,
            public val credProtect: Int? = null,
            public val largeBlob: LargeBlobResponse? = null,
            public val minPinLength: UInt? = null,
//    public val payment: = null,
        )

        @Serializable
        public data class CredPropsResponse(
            public val rk: Boolean,
        )

        @Serializable
        public enum class LargeBlobPreference(public val standardName: String) {
            Preferred("preferred"),
            Required("required");

            public companion object {
                public fun fromStandardName(standardName: String): LargeBlobPreference =
                    entries.find { it.standardName == standardName }
                        ?: throw IllegalArgumentException("$standardName is an invalid option")
            }
        }

        public enum class CredentialProtectionPolicy(public val standardName: String) {
            UserVerificationOptional("userVerificationOptional"),
            UserVerificationOptionalWithCredentialIDList("userVerificationOptionalWithCredentialIDList"),
            UserVerificationRequired("userVerificationRequired");

            public companion object {
                public fun fromStandardName(standardName: String): CredentialProtectionPolicy =
                    entries.find { it.standardName == standardName }
                        ?: throw IllegalArgumentException("$standardName is an invalid option")
            }
        }

        //@Serializable
        //public data class PaymentExtension(
        //    public val isPayment: Boolean,
        //)

        @Serializable
        public data class PublicKeyCredentialRpEntity(
            public val id: String,
            public val name: String,
        )

        public data class RegistrationOptions(
            public val attestation: Attestation = Attestation.None,
            public val attestationFormats: List<String> = emptyList(),
            public val authenticatorSelection: AuthenticatorSelectionOptions = AuthenticatorSelectionOptions(),
            public val extensions: CreateExtensions = CreateExtensions(),
            public val hints: List<Hints> = emptyList(),
            public val pubKeyCredParams: List<PublicKeyCredentialParameters> = listOf(
                PublicKeyCredentialParameters(PublicKeyAlgorithm.EdDSA),
                PublicKeyCredentialParameters(PublicKeyAlgorithm.ES256),
                PublicKeyCredentialParameters(PublicKeyAlgorithm.RS256),
                PublicKeyCredentialParameters(PublicKeyAlgorithm.PS256),
            ),
            public val user: PublicKeyCredentialUserEntity,
        )

        @Serializable
        public data class PublicKeyCredentialCreationOptions(
            public val attestation: Attestation = Attestation.None,
            public val attestationFormats: List<String> = emptyList(),
            public val authenticatorSelection: AuthenticatorSelection = AuthenticatorSelection(),
            public val challenge: String, // Base64 url-encoded
            public val excludeCredentials: List<ExistingCredential> = emptyList(),
            public val extensions: CreateExtensions = CreateExtensions(),
            public val hints: List<Hints> = emptyList(),
            public val pubKeyCredParams: List<PublicKeyCredentialParameters>,
            public val rp: PublicKeyCredentialRpEntity,
            public val timeout: Int? = null,
            public val user: PublicKeyCredentialUserEntity,
        )

        @Serializable
        public data class AuthenticatorAttestationResponse(
            public val attestationObject: String, // Base64 url-encoded
            public val clientDataJSON: String, // Base64 Json String
            public val transports: List<Transport>,
        )

        @Serializable
        public data class AttestedPublicKeyCredential(
            public val authenticatorAttachment: String,
            public val clientExtensionResults: CreateExtensionResponse?,
            public val id: String,
            public val response: AuthenticatorAttestationResponse,
        ) {
            @EncodeDefault(Mode.ALWAYS)
            public val type: String = "public-key"
        }

        @Serializable
        public data class RegisterRequest(
            public val challengeId: String,
            public val displayName: String,
            public val credential: AttestedPublicKeyCredential,
        )
    }

    public object Authentication {

        @Serializable
        public data class LargeBlob(
            public val read: Boolean?,
            public val write: String?, // Base64 url-encoded
        )

        @Serializable
        public data class LargeBlobResponse(
            public val written: Boolean,
            public val blob: String?, // Base64 url-encoded
        )

        @Serializable
        public data class RequestExtensions(
            public val appid: String? = null,
            public val largeBlob: LargeBlob? = null,
        )

        @Serializable
        public data class RequestExtensionsResponse(
            public val appid: Boolean? = null,
            public val largeBlob: LargeBlobResponse? = null,
        )

        @Serializable
        public enum class LargeBlobPreference(public val standardName: String) {
            Preferred("preferred"),
            Required("required");

            public companion object {
                public fun fromStandardName(standardName: String): LargeBlobPreference =
                    entries.find { it.standardName == standardName }
                        ?: throw IllegalArgumentException("$standardName is an invalid option")
            }
        }

        @Serializable
        public data class PublicKeyCredentialRequestOptions(
            public val allowCredentials: List<ExistingCredential> = listOf(),
            public val challenge: String,
            public val extensions: RequestExtensions = RequestExtensions(),
            public val hints: List<Hints> = emptyList(),
            public val rpId: String,
            public val timeout: Int? = null,
            public val userVerification: GeneralPreference = GeneralPreference.Preferred,
        )

        @Serializable
        public data class AssertedPublicKeyCredential(
            public val id: String, // Base64 url-encoded
            public val clientExtensionResults: RequestExtensionsResponse?,
            public val response: AuthenticatorAssertionResponse,
        ) {
            @EncodeDefault(Mode.ALWAYS)
            public val type: String = "public-key"

        }

        @Serializable
        public data class AuthenticatorAssertionResponse(
            public val authenticatorData: String, // Base64 url-encoded
            public val clientDataJSON: String, // Base64 Json String
            public val signature: String, // Base64 url-encoded
            public val userHandle: String?, // Base64 url-encoded
        )

        public data class ProveOptions(
            public val extensions: RequestExtensions = RequestExtensions(),
            public val hints: List<Hints> = emptyList(),
            public val userVerification: GeneralPreference = GeneralPreference.Preferred,
        )

        @Serializable
        public data class StartResponse(
            public val challengeId: String,
            public val options: PublicKeyCredentialRequestOptions,
        )

        @Serializable
        public data class ProveRequest(
            public val challengeId: String,
            public val credentials: AssertedPublicKeyCredential,
        )
    }
}

/*
 * TODO API Recommendations:
 *
 * 1. Consider adding helper functions for common WebAuthn operations:
 *    - Generating random challenges with appropriate entropy
 *    - Validating origin/RP ID matches
 *    - Verifying signature format before server-side verification
 *
 * 2. Consider adding configuration models for:
 *    - Timeout defaults (currently nullable Int, consider Duration type)
 *    - Challenge expiration policies
 *    - Allowed authenticator types per application security policy
 *
 * 3. The attestationObject in WebAuthNCredential contains the public key but it's Base64 encoded.
 *    Consider adding a parsed representation or helper to extract the public key for easier verification.
 *
 * 4. Sign count security: Consider adding a helper function or validation to detect sign count anomalies
 *    (backwards movement indicating cloned authenticators).
 *
 * 5. Transport hints: Consider using an enum instead of List<String> for transports to provide
 *    type safety and prevent invalid values.
 */
