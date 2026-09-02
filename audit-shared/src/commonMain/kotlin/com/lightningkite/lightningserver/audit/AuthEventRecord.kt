package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.Index
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * What happened, for an authentication event.
 *
 * Deliberately coarse: each value is a distinction an auditor would act on differently. The
 * `sessions` module recorded none of these — it had mutable *state* on a session row, which cannot
 * answer "when did this account start failing logins", because state is overwritten and a failure
 * counter is deleted on the next success. See `plans/audit-logging.md` 7.1.
 */
@Serializable
public enum class AuthEventType {
    /** A session was created — the event a `Session` row's existence only implies. */
    SessionCreated,

    /** A refresh token was exchanged for access. */
    SessionUsed,

    /** A session was ended, by its owner or by an administrator. */
    SessionTerminated,

    /** An authentication attempt was rejected. [AuthEventRecord.failureReason] says why. */
    AuthenticationFailed,

    /** A proof (password, TOTP, WebAuthN, backup code, emailed PIN) was accepted. */
    ProofAccepted,

    /** A proof was rejected. */
    ProofRejected,
}

/**
 * One authentication event: the history that a session row's mutable state cannot provide.
 *
 * The distinction that makes this a separate layer rather than a column somewhere: a `Session` row
 * records that a session *exists*, not that a login *happened*. Everything the survey in
 * `plans/audit-logging.md` 7.1 found was last-write-wins state on a row, an ephemeral cache counter
 * deleted on the next success, or a debug `println`. None of those can be counted, ordered, or
 * alerted on.
 *
 * @property requestId Joins to [RequestRecord], so an auth event and the disclosures made under the
 *   resulting session share one identifier.
 * @property principal Who the event is about — the subject id, as a string, since principals differ
 *   in key type across a deployment.
 * @property actor The principal that *caused* the event, when it differs from [principal] — an
 *   administrator terminating someone else's session, or a masquerade. Null when they are the same.
 * @property sourceIp Recorded only when actually observed. A placeholder here would read as a real
 *   origin, which is the mistake this system already made once (7.1, finding 3).
 * @property failureReason Why an attempt was rejected, for the failure event types. Populated from
 *   the `AuthFailureReason` enum in the sessions module.
 */
@GenerateDataClassPaths
@Serializable
public data class AuthEventRecord(
    override val _id: Uuid,
    @Index val requestId: Uuid,
    @Index val type: AuthEventType,
    @Index val principal: String? = null,
    val actor: String? = null,
    val sessionId: String? = null,
    val sourceIp: String? = null,
    val userAgent: String? = null,
    val failureReason: String? = null,
) : HasId<Uuid> {
    /** When the event happened, derived from the version-7 [_id]. See [RequestRecord]. */
    @OptIn(ExperimentalUuidApi::class)
    public val at: Instant
        get() = Instant.fromEpochMilliseconds(_id.epochMilliseconds)

    public companion object
}

/** As [DisclosureRecord.chainInput], for an auth event. */
public fun AuthEventRecord.chainInput(): String =
    listOf(
        _id.toString(), requestId.toString(), type.name, principal ?: "", actor ?: "",
        sessionId ?: "", sourceIp ?: "", userAgent ?: "", failureReason ?: "",
    ).joinToString(FIELD_SEPARATOR)
