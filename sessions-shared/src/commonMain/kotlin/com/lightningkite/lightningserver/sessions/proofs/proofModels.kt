package com.lightningkite.lightningserver.sessions.proofs

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant


/**
 * Completes a multi-step authentication proof by providing the final password/PIN.
 * Used when a proof method (like email or SMS) delivers a temporary code that must be submitted.
 *
 * @property key The unique identifier for this proof attempt, typically returned when initiating the proof.
 * @property password The verification code or password received via the proof method (e.g., PIN from email/SMS).
 *           **Security Note**: This field contains sensitive authentication data and should be transmitted over secure channels only.
 */
@Serializable
public data class FinishProof(
    val key: String,
    val password: String
)

/**
 * Combines user identification with a password for authentication.
 * Used for traditional username/password login flows.
 *
 * @property type The type of identification being used (e.g., "User", "Account").
 * @property property The specific property being used to identify the user (e.g., "email", "username", "phoneNumber").
 * @property value The actual identification value (e.g., "user@example.com", "john_doe").
 * @property password The user's password for authentication.
 *           **Security Note**: This field contains sensitive credentials and must be transmitted over HTTPS only.
 */
@Serializable
public data class IdentificationAndPassword(
    val type: String,
    val property: String,
    val value: String,
    val password: String
)


/**
 * Identifies a user or entity without providing authentication credentials.
 * Used to specify which user is attempting to authenticate before proof methods are selected.
 *
 * @property type The type of entity being identified (e.g., "User", "Account", "Session").
 * @property property The specific property being used to identify the entity (e.g., "email", "username", "id").
 *           Null when only the type is needed for identification.
 * @property value The actual identification value (e.g., "user@example.com", "12345").
 *           Null when only the type and property are needed.
 */
@Serializable
public data class Identification(
    val type: String,
    val property: String?,
    val value: String?,
)

/**
 * Describes an available authentication proof method and its security characteristics.
 * Used to communicate which authentication methods are available to the user.
 *
 * @property via The mechanism used for proof delivery (e.g., "email", "sms", "password", "oauth", "device").
 * @property property The specific property associated with this proof method (e.g., "email", "phoneNumber").
 *           Null for methods that don't require a specific property (e.g., "password").
 * @property strength The security strength of this proof method. Higher values indicate stronger authentication.
 *           Default is 1. Multi-factor authentication may require combining methods to reach a minimum strength threshold.
 */
@Serializable
public data class ProofMethodInfo(
    val via: String,
    val property: String?,
    val strength: Int = 1,
)

/**
 * Represents a specific authentication option available to a user, combining the proof method with the target value.
 * Part of presenting authentication choices to the user during login.
 *
 * @property method The authentication method information including delivery mechanism and strength.
 * @property value The specific value where the proof will be sent (e.g., "user@example.com", "+1234567890").
 *           Null if the value should not be disclosed to the user (e.g., for privacy reasons) or not applicable.
 */
@Serializable
public data class ProofOption(
    val method: ProofMethodInfo,
    val value: String? = null,
)

/**
 * Defines the authentication requirements and available proof options for accessing a resource or completing an action.
 * Used in multi-factor authentication scenarios where users must provide sufficient proof strength.
 *
 * @property options The list of available authentication methods the user can choose from.
 *           Multiple proofs may need to be combined to meet the strength requirement.
 * @property strengthRequired The minimum total strength value required for successful authentication.
 *           The sum of all proof strengths provided must meet or exceed this value.
 */
@Serializable
public data class AuthRequirements(
    val options: List<ProofOption>,
    val strengthRequired: Int,
)

/**
 * Represents a completed authentication proof with cryptographic verification.
 * This is the server-generated evidence that authentication was successfully completed.
 *
 * @property via The mechanism used for this proof (e.g., "email", "sms", "password", "oauth").
 * @property strength The security strength value of this proof method.
 * @property property The property that was verified (e.g., "email", "phoneNumber", "password").
 * @property value The actual value that was verified (e.g., "user@example.com").
 * @property at The timestamp when this proof was completed.
 * @property expiresAt The timestamp when this proof is no longer valid.
 * @property signature A cryptographic signature that validates this proof's authenticity.
 *           **Security Note**: This prevents tampering and ensures the proof was issued by the server.
 */
@Serializable
public data class Proof(
    val via: String,
    val strength: Int = 1,
    val property: String,
    val value: String,
    val at: Instant,
    val expiresAt: Instant?, // Nullable for now for backwards compatibility with mobile apps
    val signature: String,
)

/**
 * Configuration for "remember this device" functionality in authentication flows.
 * Allows reduced authentication requirements for known/trusted devices.
 *
 * @property duration How long the device should be considered "known" before requiring full re-authentication.
 * @property strength The authentication strength value granted by device recognition.
 *           This strength can reduce the total proof strength needed on subsequent logins.
 */
@Serializable
public data class KnownDeviceOptions(
    val duration: Duration,
    val strength: Int
)

/**
 * Contains the device recognition credentials issued to a trusted device.
 * Stored client-side to enable reduced authentication requirements on return visits.
 *
 * @property secret A cryptographic secret that identifies this specific device to the server.
 *           **Security Note**: This value must be kept secure on the client device. If compromised,
 *           an attacker could impersonate this device and bypass authentication requirements.
 * @property expiresAt The timestamp when this device secret expires and full authentication is required again.
 */
@Serializable
public data class KnownDeviceSecretAndExpiration(
    val secret: String,
    val expiresAt: Instant
)