package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.Index
import com.lightningkite.services.data.UuidV7
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.TypedId
import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Represents one authentication event, such as a rejected session or an accepted proof.
 *
 * Unlike a `Session` row, which only shows that a session exists, these rows record each login and
 * failed attempt, so they can be counted, ordered and alerted on.
 *
 * @property origin The request or other origin the event happened under, shared with the disclosures
 *   made during it.
 * @property principal The subject id the event is about, or the address for an address-based proof such as an emailed PIN.
 * @property sessionId The session the event is about, for [Type.AuthenticationFailed], [Type.SessionCreated]
 *   and [Type.SessionTerminated].
 * @property sourceIp The IP address the attempt came from, or `null` if none was observed.
 * @property userAgent The user agent the attempt sent, or `null` if none was observed.
 * @property failureReason Why the attempt was rejected, for [Type.AuthenticationFailed] and [Type.ProofRejected].
 * @property method The proof method's `via`, such as `"password"` or `"email"`, or `null` for a refresh token.
 * @property methodProperty The property [method] was applied to, such as the `email` of an emailed PIN,
 *   or `null` if the method identifies the subject directly.
 */
@GenerateDataClassPaths
@Serializable
public data class AuthEventRecord(
    override val _id: ID,
    @Index val origin: OriginRecord.ID,
    @Index val type: Type,
    @Index val principal: String,
    val sessionId: Uuid? = null,
    val sourceIp: String? = null,
    val userAgent: String? = null,
    val failureReason: String? = null,
    val method: String? = null,
    val methodProperty: String? = null,
) : HasId<AuthEventRecord.ID> {
    @Serializable
    @JvmInline
    public value class ID(override val raw: UuidV7) : TypedId<UuidV7, ID>

    /** Represents what kind of authentication event happened. */
    @Serializable
    public enum class Type {
        /** A refresh token naming a real session was rejected for [failureReason]. */
        AuthenticationFailed,

        /** A session was created, such as when a user logs in. */
        SessionCreated,

        /** A session was ended, such as when a user logs out. */
        SessionTerminated,

        /** A proof (password, TOTP, WebAuthN, backup code, emailed PIN, OAuth) was accepted. */
        ProofAccepted,

        /** A credential presented for a real account was rejected for [failureReason]. */
        ProofRejected,

        /** A proof was sent to someone without any credential being presented, as with a magic link. */
        ProofIssued,
    }

    /** Returns when the event happened, taken from the version 7 [_id]. */
    public val at: Instant
        get() = _id.timestamp()
}

