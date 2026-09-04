package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.sessions.proofs.extensions.TooManyAttemptsException
import com.lightningkite.lightningserver.sessions.proofs.extensions.constrainAttemptRate
import com.lightningkite.services.cache.Cache
import kotlinx.serialization.Serializable

/**
 * Why a credential presented to a proof endpoint was rejected.
 *
 * Separate from `AuthFailureReason` rather than an extension of it, for two reasons. The reasons
 * there describe a *refresh token* being exchanged, which is a different act from presenting a
 * credential — a token attests to an authentication that already happened, a proof is the
 * authentication. And every value there must answer `reachableWithoutCredentials`, which decides
 * whether the reason is suppressed; that question is meaningless here, because proof failures are
 * all recorded (see [reportProofRejected]). Folding these in would drag that suppression along.
 *
 * Deliberately coarse, like its neighbour: each value is a distinction an auditor would act on
 * differently. "Which of this account's three passwords didn't match" is detail, not a new constant.
 */
@Serializable
public enum class ProofFailureReason {
    /**
     * The attempt could not be read as identifying anyone: an unrecognised principal type, an
     * unparseable identifier, or an external profile carrying no identifying property.
     */
    MalformedRequest,

    /**
     * Rejected by the attempt limiter before the credential was looked at.
     *
     * Distinct from the failures that fed the limiter, which were each recorded on their own: this
     * one says the attempts *continued* past the point where the server stopped answering them,
     * which is the difference between a user who forgot a password and a script that did not stop.
     */
    RateLimited,

    /**
     * The identifying property and value matched no account.
     *
     * Enumeration-adjacent by nature, and deliberately indistinguishable from [SecretMismatch] in
     * what the endpoint answers the caller — the difference lives only here. the audit record's
     * `principal` stays null for these: there is no account to name, and inventing one would make
     * the log assert something that did not happen.
     */
    NoSuchSubject,

    /** An account or credential record resolved, and the presented secret did not match it. */
    SecretMismatch,

    /** The PIN, challenge, or code existed but is past its window or out of attempts. */
    SecretExpired,

    /** A single-use credential was presented a second time. */
    SecretAlreadyUsed,
}

/**
 * Records that a credential presented to a proof endpoint was rejected.
 *
 * ## Why proof endpoints, specifically
 * `SessionManager.authFailed` covers refresh-token rejections, which are mostly expired sessions.
 * Credential *guessing* happens here: passwords, PINs, TOTP, backup codes, WebAuthN. Without this,
 * "how many failures has this account had in the last ten minutes" is unanswerable for every
 * interesting kind of failure.
 *
 * ## Every failure is recorded, including ones that resolved no account
 * There is deliberately no `reachableWithoutCredentials`-style suppression here, and the token path's
 * guard must not be copied over. That guard exists because a refresh token is unsigned, so an
 * attacker can mint rejections at will and turn the audit table into an amplification target. Proof
 * endpoints are different: each one is behind `constrainAttemptRate`, which caps how fast a given
 * key can produce failures at all. A failure against an account that does not exist is exactly the
 * evidence of enumeration an auditor wants, so dropping it would remove the record of the attack
 * this layer is for.
 *
 * @param method Which proof method rejected the attempt; its `via` and `property` land on the record.
 * @param reason Countable, so it can be alerted on. Never a sentence.
 * @param principal The account the attempt was against, where one resolved. Null otherwise — see
 *   [ProofFailureReason.NoSuchSubject]; a proof endpoint that resolved nothing has no subject to
 *   name, and naming one anyway would be a fabrication.
 * @param request The request the attempt arrived on, for its origin. Only what was actually
 *   observed is recorded; a missing user agent stays null rather than becoming a blank string.
 */
context(server: ServerRuntime)
public suspend fun reportProofRejected(
    method: ProofMethodInfo,
    reason: ProofFailureReason,
    principal: String? = null,
    request: Request<*>? = null,
): Unit = reportProofEvent("ProofRejected", method, principal, request, reason.name)

/**
 * Records that a credential presented to a proof endpoint was accepted.
 *
 * Raised where the acceptance happens — at the end of a `prove` handler, after the secret has been
 * checked — and deliberately not where proofs are *minted*. `Signer.makeProof` also mints a proof to
 * be mailed to someone (a magic link), which is not a credential having been presented and must not
 * be recorded as one. See the module notes on that split.
 *
 * @param principal The account that authenticated. For a method that proves ownership of an address
 *   rather than of an account (an emailed or texted PIN), this is that address: those endpoints
 *   never resolve a subject at all — resolution happens later, at login — so the address is the
 *   truthful answer to "who was this about".
 */
context(server: ServerRuntime)
public suspend fun reportProofAccepted(
    method: ProofMethodInfo,
    principal: String? = null,
    request: Request<*>? = null,
): Unit = reportProofEvent("ProofAccepted", method, principal, request, null)

context(server: ServerRuntime)
private suspend fun reportProofEvent(
    type: String,
    method: ProofMethodInfo,
    principal: String?,
    request: Request<*>?,
    detail: String?,
) {
    // Reporters must not throw (see AuthEventReporter): a rejection path is already failing, and a
    // second failure raised here would obscure the first.
    server.server.authEventReporters.forEach {
        it.report(
            type = type,
            principal = principal,
            sourceIp = request?.sourceIp,
            userAgent = request?.headers?.get(HttpHeader.UserAgent)?.root,
            detail = detail,
            method = method.via,
            methodProperty = method.property,
        )
    }
}

/**
 * [constrainAttemptRate] for a proof endpoint: the same limiter, with the rejection it raises
 * recorded as a proof failure.
 *
 * Wrapped here rather than caught at each `prove` handler because the limiter answers *before* the
 * handler body runs, so the only place to see it is around the call — and writing that out at every
 * limited `prove` would bury each handler's actual logic in a try/catch.
 *
 * @param method The proof method whose attempt was limited.
 * @param request The request the attempt arrived on, for its origin.
 */
context(server: ServerRuntime)
public suspend inline fun <R> Cache.constrainProofAttemptRate(
    cacheKey: String,
    method: ProofMethodInfo,
    request: Request<*>?,
    action: () -> R,
): R = try {
    constrainAttemptRate(cacheKey, action = action)
} catch (e: TooManyAttemptsException) {
    // The attempts that filled the bucket were each recorded on their own; this one says they did not
    // stop, which is the difference between a forgotten password and a script. No principal: the
    // limiter turns the request away before any account is looked up, and looking one up here would
    // hand an attacker a database query per blocked request.
    reportProofRejected(method, ProofFailureReason.RateLimited, request = request)
    throw e
}
