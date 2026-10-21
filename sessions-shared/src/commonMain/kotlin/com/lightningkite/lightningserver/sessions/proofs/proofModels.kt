package com.lightningkite.lightningserver.sessions.proofs

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant


/**
 * Completes a proof whose method delivered a temporary code, such as email or SMS.
 *
 * @property key The identifier of the proof attempt, returned when it was begun.
 * @property password The code received, which is sensitive and must only be sent over secure channels.
 */
@Serializable
public data class FinishProof(
    val key: String,
    val password: String,
)

/**
 * Holds the identification and password of a username and password login.
 *
 * @property type The type of entity being identified, such as `User`.
 * @property property The property that identifies the user, such as `email` or `username`.
 * @property value The identifying value, such as `user@example.com`.
 * @property password The user's password, which must only be sent over HTTPS.
 */
@Serializable
public data class IdentificationAndPassword(
    val type: String,
    val property: String,
    val value: String,
    val password: String,
)


/**
 * Identifies who is attempting to authenticate, before any proof method is chosen.
 *
 * @property type The type of entity being identified, such as `User`.
 * @property property The property that identifies the entity, such as `email`, or `null` if only the type is needed.
 * @property value The identifying value, or `null` if only the type and property are needed.
 */
@Serializable
public data class Identification(
    val type: String,
    val property: String?,
    val value: String?,
)

/**
 * Describes an available proof method and its strength.
 *
 * @property via The mechanism of the proof, such as `email`, `sms`, `password`, `oauth` or `device`.
 * @property property The property the proof verifies, such as `email`, or `null` if it verifies none, as with a password.
 * @property strength How strong the proof is, with proofs combined to reach a required total. The default is 1.
 */
@Serializable
public data class ProofMethodInfo(
    val via: String,
    val property: String?,
    val strength: Int = 1,
)

/**
 * Represents a proof method together with where its proof will be sent, as one of the choices presented during login.
 *
 * @property value Where the proof will be sent, such as `user@example.com`, or `null` if it is not applicable or must not be disclosed.
 */
@Serializable
public data class ProofOption(
    val method: ProofMethodInfo,
    val value: String? = null,
)

/**
 * Describes the proof options available to a user and the total strength they must reach.
 *
 * @property options The proof methods the user can choose from, which may need to be combined.
 * @property strengthRequired The minimum sum of the strengths of the proofs provided.
 */
@Serializable
public data class AuthRequirements(
    val options: List<ProofOption>,
    val strengthRequired: Int,
)

/**
 * Represents a server-issued proof that a property was verified.
 *
 * @property via The mechanism of the proof, such as `email`, `sms`, `password` or `oauth`.
 * @property property The property that was verified, such as `email`.
 * @property value The value that was verified, such as `user@example.com`.
 * @property at When the proof was completed.
 * @property expiresAt When the proof stops being valid, which is nullable for backwards compatibility with mobile apps.
 * @property signature The signature that shows the proof was issued by the server and not tampered with.
 */
@Serializable
public data class Proof(
    val via: String,
    val strength: Int = 1,
    val property: String,
    val value: String,
    val at: Instant,
    val expiresAt: Instant? = null, // Nullable for now for backwards compatibility with mobile apps
    val signature: String,
)

/**
 * Configures "remember this device", which reduces the authentication required on a known device.
 *
 * @property duration How long a device stays known before full re-authentication is required.
 * @property strength The proof strength granted by recognizing the device.
 */
@Serializable
public data class KnownDeviceOptions(
    val duration: Duration,
    val strength: Int,
)

/**
 * Holds the secret issued to a trusted device, which the client stores to reduce the authentication required on return visits.
 *
 * @property secret The secret identifying the device, which must be kept secure since anyone holding it can impersonate the device.
 * @property expiresAt When the secret expires and full authentication is required again.
 */
@Serializable
public data class KnownDeviceSecretAndExpiration(
    val secret: String,
    val expiresAt: Instant,
)