package com.lightningkite.lightningserver.auth

import kotlinx.serialization.Serializable

/**
 * What happened, for an authentication event.
 *
 * Lives beside the seam that raises these rather than beside the record that stores them: the
 * vocabulary belongs to whoever reports an event, and an audit layer is only one possible consumer.
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

    /**
     * A proof was minted and sent to someone with no credential presented by anyone — a magic link.
     *
     * Separate from [ProofAccepted] because nothing was proven: the server decided to trust an
     * address and mailed a bearer credential to it. Whoever reads that message can authenticate as
     * the address, so issuance is itself the act worth tracking — an attacker who can cause a link
     * to be issued to an address they control needs no credential at all.
     */
    ProofIssued,
}
