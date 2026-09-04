package com.lightningkite.lightningserver.sessions

import kotlinx.serialization.Serializable

/**
 * Why an authentication attempt did not succeed.
 *
 * These are the reasons the session path already distinguished as debug strings; naming them makes
 * the distinction survive past a developer's console. The auth event log
 * (`plans/audit-logging.md` section 7) needs "a failed attempt, and why" as data — "wrong secret"
 * and "session was terminated" are different security events, and a free-text string cannot be
 * counted, filtered, or alerted on.
 *
 * Deliberately coarse: each value is a distinction an auditor would act on differently. Anything
 * finer belongs in the accompanying detail, not in a new constant.
 *
 * @property reachableWithoutCredentials Whether an attacker holding no valid credentials can cause
 *   this reason at will. See the property's own documentation — it decides whether the reason is
 *   safe to record, and every value has to answer it.
 */
@Serializable
public enum class AuthFailureReason(
    /**
     * Whether an attacker with no credentials at all can trigger this reason on demand.
     *
     * A refresh token is not signed: [RefreshToken.valid] is a prefix check, and the type and
     * session id are read straight back out of the attacker's own string. So every rejection that
     * happens *before* the session secret is verified against its hash can be produced at will by
     * anyone who can reach the server, as many times as they like.
     *
     * Recording those is an amplification vector rather than an audit trail. One forged token per
     * request means one audit row per request, written by an unauthenticated caller, into the same
     * database as the fail-closed disclosure and data access logs — so filling it takes the server
     * down with it. They are also worthless as evidence: the session id in the record is a number
     * the attacker picked, so the row attests to nothing that happened.
     *
     * The two rejections that *follow* the hash check, and the two that require naming a real
     * session, are not reachable this way: a session id is a 122-bit random UUID, so an attacker
     * cannot produce one, and the hash check cannot be passed without the secret. Those are real
     * events about a real account and are recorded.
     *
     * There is deliberately no default. A new reason has to state which side of the credential
     * check it falls on, because getting it wrong silently reopens the hole — which is exactly what
     * happened when this was a two-value check inside `authFailed` and [NoSuchSession] was added
     * without anyone noticing it belonged in the list.
     */
    public val reachableWithoutCredentials: Boolean,
) {
    /** The token did not parse, or carried no usable session reference. */
    TokenMalformed(reachableWithoutCredentials = true),

    /** The token's declared type does not match the principal handler it was presented to. */
    TokenTypeMismatch(reachableWithoutCredentials = true),

    /**
     * The token was well-formed but names a session that does not exist.
     *
     * Forgeable: the session id comes out of the token string, so any well-formed token naming any
     * random UUID lands here. A genuine occurrence — a client retrying against a restored database
     * — is indistinguishable from a forged one, so there is nothing here worth recording. That the
     * request happened at all is already in the request log.
     */
    NoSuchSession(reachableWithoutCredentials = true),

    /** The subject exists but is not currently permitted to authenticate — deactivated, suspended. */
    AuthenticationNotPermitted(reachableWithoutCredentials = false),

    /** The presented secret did not match the session's stored hash. */
    SecretMismatch(reachableWithoutCredentials = false),

    /** Past the session's hard expiry. */
    SessionExpired(reachableWithoutCredentials = false),

    /** Past the session's sliding staleness window. */
    SessionStale(reachableWithoutCredentials = false),

    /** The session was explicitly terminated. */
    SessionTerminated(reachableWithoutCredentials = false),
}
