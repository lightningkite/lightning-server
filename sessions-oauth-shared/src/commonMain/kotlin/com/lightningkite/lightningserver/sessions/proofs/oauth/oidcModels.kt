package com.lightningkite.lightningserver.sessions.proofs.oauth

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.HasId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Standard OpenID Connect ID Token claims, plus the commonly-shipped profile claims.
 *
 * Only fields that are part of the OIDC core spec or universally supported by IdPs are typed here.
 * Provider-specific extensions can be read from [extra] when needed.
 *
 * @property iss Issuer Identifier. Must match the IdP's published issuer.
 * @property sub Subject Identifier. Stable, unique identifier for the end-user at the IdP.
 * @property aud Audience(s). Always contains our `client_id`. May be a single string or an
 *   array in the wire format; [AudSerializer] normalizes to a list.
 * @property exp Expiration time (epoch seconds). Token MUST NOT be accepted after this.
 * @property iat Issued-at time (epoch seconds).
 * @property nbf Not-before time (epoch seconds).
 * @property nonce Replay-protection nonce. MUST equal the value sent in the auth request.
 * @property email End-user's preferred email address.
 * @property email_verified True if the IdP has verified the email. Trust only when true.
 * @property name End-user's full name in displayable form.
 * @property picture URL of the end-user's profile picture.
 * @property preferred_username Shorthand name the user wants to be referred to. Often the email
 *   for enterprise IdPs that don't expose `email` directly.
 */
@Serializable
public data class OidcIdTokenClaims(
    val iss: String,
    val sub: String,
    @Serializable(with = AudSerializer::class)
    val aud: List<String>,
    val exp: Long,
    val iat: Long,
    val nbf: Long? = null,
    val nonce: String? = null,
    val email: String? = null,
    val email_verified: Boolean? = null,
    val name: String? = null,
    val picture: String? = null,
    val preferred_username: String? = null,
)

/**
 * Serializer for the OIDC `aud` claim, which the OIDC core spec allows as either a single string
 * or a JSON array of strings. Both shapes are accepted on read; writes always produce a JSON array
 * so round-tripping is stable.
 */
public object AudSerializer : KSerializer<List<String>> {
    private val delegate = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<String> {
        val json = (decoder as? JsonDecoder)
            ?: throw SerializationException("AudSerializer requires a JSON decoder")
        return when (val element = json.decodeJsonElement()) {
            is JsonPrimitive -> if (element.isString) listOf(element.content)
            else throw SerializationException("aud primitive must be a string")
            is JsonArray -> element.map {
                (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                    ?: throw SerializationException("aud array must contain only strings")
            }
            is JsonObject -> throw SerializationException("aud must be a string or array of strings, got object")
        }
    }

    override fun serialize(encoder: Encoder, value: List<String>) {
        delegate.serialize(encoder, value)
    }
}

/**
 * Per-tenant configuration for an OpenID Connect identity provider.
 *
 * Each row represents one customer's IdP (e.g., "ACME's Okta tenant"). The tenant slug is the
 * primary key and appears in the login/callback URL path so different customers can plug in
 * different IdPs without code changes.
 *
 * **Client secret handling.** The wire field [clientSecret] is bidirectionally asymmetric:
 * - On write (insert), it holds the PLAINTEXT secret submitted by the admin. The endpoint
 *   layer encrypts it with the server's `secretBasis` AES-GCM cipher before the row is
 *   persisted.
 * - At rest, it stores the base64-encoded ciphertext. Reads at the storage layer return that
 *   ciphertext, which the endpoint layer decrypts when needed.
 * - On read through the admin REST surface, a read mask blanks the field to a sentinel
 *   (`"********"`); the plaintext is never re-exposed.
 * Direct PATCH of this field is forbidden by `updateRestrictions`; use the rotate-secret
 * endpoint to change it.
 *
 * @property _id Tenant slug used in URLs (e.g., `/proof/oidc/acme/login`). Keep it short,
 *   URL-safe, and stable; reusing a slug across tenants is dangerous.
 * @property niceName Human-readable name shown in UI.
 * @property discoveryUrl Absolute HTTPS URL to the IdP's `.well-known/openid-configuration`.
 * @property clientId Public OAuth client identifier registered with the IdP.
 * @property clientSecret See "Client secret handling" above. Stored encrypted; never log.
 * @property emailClaim Which id_token claim to read the user's email from. Almost always
 *   `email`; switch only for IdPs that put email in `preferred_username` or `upn`.
 * @property requireEmailVerified Reject ID tokens whose `email_verified` claim is not true.
 *   Default true; only disable for IdPs whose verification semantics are known to be
 *   equivalent (e.g., enterprise-managed accounts).
 * @property strength Proof strength awarded for a successful login through this tenant.
 *   Default 10; raise for IdPs that enforce MFA, lower if you have any reason to weight
 *   this provider below other identity proofs.
 * @property extraScopes Additional OIDC scopes to request beyond the defaults
 *   (`openid email profile`).
 * @property disabledAt If set, this tenant cannot be used to log in. Useful for offboarding
 *   without deleting historical records.
 */
@GenerateDataClassPaths
@Serializable
public data class OidcTenantConfig(
    override val _id: String,
    val niceName: String,
    val discoveryUrl: String,
    val clientId: String,
    val clientSecret: String,
    val emailClaim: String = "email",
    val requireEmailVerified: Boolean = true,
    val strength: Int = 10,
    val extraScopes: Set<String> = setOf(),
    val disabledAt: Instant? = null,
    val createdAt: Instant = Clock.System.now(),
) : HasId<String>
