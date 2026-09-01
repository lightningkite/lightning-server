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
 */
@Serializable
public enum class AuthFailureReason {
    /** The token did not parse, or carried no usable session reference. */
    TokenMalformed,

    /** The token's declared type does not match the principal handler it was presented to. */
    TokenTypeMismatch,

    /** The token was well-formed but names a session that does not exist. */
    NoSuchSession,

    /** The subject exists but is not currently permitted to authenticate — deactivated, suspended. */
    AuthenticationNotPermitted,

    /** The presented secret did not match the session's stored hash. */
    SecretMismatch,

    /** Past the session's hard expiry. */
    SessionExpired,

    /** Past the session's sliding staleness window. */
    SessionStale,

    /** The session was explicitly terminated. */
    SessionTerminated,
}
