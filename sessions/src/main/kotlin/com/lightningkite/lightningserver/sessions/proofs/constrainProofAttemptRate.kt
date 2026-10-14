package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.sessions.reportAuthEvent
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.sessions.AuthEvent
import com.lightningkite.lightningserver.sessions.proofs.extensions.TooManyAttemptsException
import com.lightningkite.lightningserver.sessions.proofs.extensions.constrainAttemptRate
import com.lightningkite.services.cache.Cache

/**
 * Runs [action] under [constrainAttemptRate], recording a rejection as an [AuthEvent.ProofRejected] with reason [AuthEvent.ProofRejected.Reason.RateLimited].
 *
 * @param method The proof method whose attempt was limited.
 * @param request The request the attempt arrived on, if any.
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
    server.reportAuthEvent(AuthEvent.ProofRejected(method, AuthEvent.ProofRejected.Reason.RateLimited, request = request))
    throw e
}
