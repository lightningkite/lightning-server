package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.sessions.proofs.TotpHashAlgorithm
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.IndexSet
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Represents a time-based one-time password (TOTP) secret bound to a subject, as used by authenticator apps.
 *
 * The [secretBase32] must be stored securely and never exposed to clients after initial setup.
 *
 * @property subjectType The type of the subject, such as `User`.
 * @property label The label shown in authenticator apps.
 * @property issuer The issuer name shown in authenticator apps, typically your application's name.
 * @property period The time window of each code, typically 30 seconds.
 * @property digits The number of digits in a code, typically 6 or 8.
 * @property expiresAt When this secret stops being valid, or `null` if it does not expire.
 * @property disabledAt When this secret was disabled, which makes it unusable even if not expired.
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
 * Represents a hashed password bound to a subject.
 *
 * @property hash The password hash, never the plaintext.
 * @property hint A hint to help the user remember the password, which must not reveal it.
 * @property expiresAt When the password must be changed, or `null` if it does not expire.
 * @property disabledAt When this password was disabled, which prevents its use.
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
 * Represents a secret stored on a trusted device, which enables "remember this device" logins.
 *
 * @property hash The hash of the device secret.
 * @property deviceInfo A description of the device for the user to see, such as `Chrome on Windows`, which is not used for authentication.
 * @property expiresAt When the device must be re-verified, or `null` if it does not expire.
 * @property disabledAt When trust for this device was revoked.
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
 * Holds the password and optional hint a user sets or changes.
 *
 * The password arrives in plaintext, so validate its strength, hash it immediately, and never log it.
 */
@Serializable
public data class EstablishPassword(
    val password: String,
    val hint: String? = null,
)

/**
 * Asks the server to generate a new TOTP secret and return it with a QR code for an authenticator app.
 *
 * @property label The label shown in the user's authenticator app, with a default used if `null`.
 */
@Serializable
public data class EstablishTotp(
    val label: String? = null,
)