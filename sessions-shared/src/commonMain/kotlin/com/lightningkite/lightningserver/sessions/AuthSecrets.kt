package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.sessions.proofs.TotpHashAlgorithm
import com.lightningkite.services.data.*
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Represents a Time-based One-Time Password (TOTP) secret for multi-factor authentication.
 *
 * TOTP secrets are used for generating time-based authentication codes (like Google Authenticator).
 * Each secret is bound to a specific subject (user) and can be configured with different algorithms
 * and code generation parameters.
 *
 * Security considerations:
 * - The [secretBase32] should be stored securely and never exposed to clients after initial setup
 * - Secrets should be checked for expiration ([expiresAt]) and disabled status ([disabledAt])
 * - Track [lastUsedAt] to detect inactive or potentially compromised secrets
 * - Consider rotating secrets periodically for enhanced security
 *
 * @property _id Unique identifier for this TOTP secret
 * @property subjectType Type identifier for the subject (e.g., "User", "Admin")
 * @property subjectId Unique identifier of the subject (user) this secret belongs to
 * @property label Human-readable label for this authenticator (shown in authenticator apps)
 * @property secretBase32 The Base32-encoded shared secret used to generate TOTP codes. Must be kept confidential.
 * @property issuer The issuer name displayed in authenticator apps (typically your application/company name)
 * @property period Time window duration for code generation (typically 30 seconds)
 * @property digits Number of digits in the generated code (typically 6 or 8)
 * @property algorithm Hash algorithm used for TOTP generation (SHA1, SHA256, or SHA512)
 * @property establishedAt Timestamp when this TOTP secret was first created
 * @property lastUsedAt Timestamp of the last successful authentication using this secret, or null if never used
 * @property expiresAt Optional expiration timestamp after which this secret is no longer valid
 * @property disabledAt Optional timestamp when this secret was disabled, making it unusable even if not expired
 */
@Serializable
@GenerateDataClassPaths
@IndexSet(["subjectId", "subjectType", "expiresAt", "disabledAt"])
public data class TotpSecret(
    override val _id: Uuid = Uuid.random(),
    val subjectType: String,
    val subjectId: String,
    val label: String,

    val secretBase32: String,
    val issuer: String,
    val period: Duration,
    val digits: Int,
    val algorithm: TotpHashAlgorithm,

    val establishedAt: Instant,
    val lastUsedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val disabledAt: Instant? = null,
) : HasId<Uuid>

/**
 * Represents a hashed password credential for user authentication.
 *
 * Password secrets store hashed passwords (never plaintext) for user authentication.
 * Each secret is bound to a specific subject (user) and can optionally include a hint
 * to help users remember their password.
 *
 * Security considerations:
 * - The [hash] should be a cryptographically strong hash (e.g., bcrypt, argon2, scrypt)
 * - Never store plaintext passwords - always hash before storing
 * - Password hints ([hint]) should not reveal the password itself
 * - Implement password expiration policies using [expiresAt]
 * - Track [lastUsedAt] to detect inactive accounts or credential stuffing attempts
 * - Enforce strong password policies before hashing
 * - Consider implementing rate limiting on password verification attempts
 *
 * @property _id Unique identifier for this password secret
 * @property subjectType Type identifier for the subject (e.g., "User", "Admin")
 * @property subjectId Unique identifier of the subject (user) this password belongs to
 * @property hash The cryptographically hashed password. Never store plaintext passwords.
 * @property hint Optional hint to help the user remember their password. Should not reveal the password.
 * @property establishedAt Timestamp when this password was first created or last changed
 * @property lastUsedAt Timestamp of the last successful authentication using this password, or null if never used
 * @property expiresAt Optional expiration timestamp after which the password must be changed
 * @property disabledAt Optional timestamp when this password was disabled, preventing its use
 */
@Serializable
@GenerateDataClassPaths
@IndexSet(["subjectId", "subjectType", "expiresAt", "disabledAt"])
public data class PasswordSecret(
    override val _id: Uuid = Uuid.random(),
    val subjectType: String,
    val subjectId: String,

    val hash: String,
    val hint: String? = null,

    val establishedAt: Instant,
    val lastUsedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val disabledAt: Instant? = null,
) : HasId<Uuid>

/**
 * Represents a trusted device credential for device-based authentication.
 *
 * Known device secrets enable "remember this device" functionality, where a device can be
 * authenticated based on a secret stored on that device. This is commonly used to reduce
 * friction in multi-factor authentication by trusting devices that have been previously verified.
 *
 * Security considerations:
 * - The [hash] should be a cryptographically strong hash of the device secret
 * - Device secrets should be unique per device and difficult to predict
 * - Implement device expiration using [expiresAt] (e.g., 30-90 days)
 * - Allow users to revoke device trust by setting [disabledAt]
 * - Store [deviceInfo] for user visibility but don't use it for authentication
 * - Consider IP address and user-agent validation in addition to the device secret
 * - Track [lastUsedAt] to detect compromised device credentials
 * - Limit the number of known devices per user
 *
 * @property _id Unique identifier for this device secret
 * @property subjectType Type identifier for the subject (e.g., "User", "Admin")
 * @property subjectId Unique identifier of the subject (user) this device belongs to
 * @property hash The cryptographically hashed device secret. Verified against the device's presented secret.
 * @property deviceInfo Human-readable device information (e.g., "Chrome on Windows", "iPhone 12")
 * @property establishedAt Timestamp when this device was first trusted
 * @property lastUsedAt Timestamp of the last successful authentication from this device, or null if never used
 * @property expiresAt Optional expiration timestamp after which the device must be re-verified
 * @property disabledAt Optional timestamp when trust for this device was revoked
 */
@Serializable
@GenerateDataClassPaths
@IndexSet(["subjectId", "subjectType", "expiresAt", "disabledAt"])
public data class KnownDeviceSecret(
    override val _id: Uuid = Uuid.random(),
    val subjectType: String,
    val subjectId: String,

    val hash: String,
    val deviceInfo: String,

    val establishedAt: Instant,
    val lastUsedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val disabledAt: Instant? = null,
) : HasId<Uuid>

/**
 * Request data for establishing a new password credential.
 *
 * This class is used when a user wants to set up or change their password.
 * The password should be validated for strength before being hashed and stored
 * as a [PasswordSecret].
 *
 * Security considerations:
 * - Always validate password strength before accepting (minimum length, complexity, etc.)
 * - Hash the password immediately upon receipt using a strong algorithm
 * - Never log or persist the plaintext password
 * - Consider checking against known compromised password databases
 * - Transmit only over encrypted connections (HTTPS)
 * - Validate that the hint doesn't contain the password or obvious derivatives
 *
 * @property password The plaintext password to be hashed and stored. Should be validated for strength.
 * @property hint Optional hint to help the user remember their password. Should not reveal the password.
 */
@Serializable
public data class EstablishPassword(
    val password: String,
    val hint: String? = null
)

/**
 * Request data for establishing a new TOTP (Time-based One-Time Password) authenticator.
 *
 * This class is used when a user wants to set up TOTP-based multi-factor authentication.
 * The server will generate a new secret and return it (along with a QR code) to the client
 * for scanning with an authenticator app.
 *
 * Typical flow:
 * 1. Client sends EstablishTotp request with optional label
 * 2. Server generates a new TOTP secret and returns it with QR code
 * 3. User scans QR code in their authenticator app
 * 4. User provides a test code to verify setup
 * 5. Server activates the TOTP secret
 *
 * @property label Optional human-readable label for this authenticator entry. If not provided,
 *                 a default label may be used (e.g., user's email or username). This label appears
 *                 in the user's authenticator app to distinguish between multiple accounts.
 */
@Serializable
public data class EstablishTotp(
    val label: String? = null
)