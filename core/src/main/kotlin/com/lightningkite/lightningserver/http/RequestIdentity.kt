package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.runtime.Engine
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The identifiers correlating one logical request across every log the server writes.
 *
 * @property requestId The authoritative identifier. Always server-controlled: either generated here
 *   or adopted from a reverse proxy the deployment has explicitly declared trustworthy.
 * @property upstreamRequestId Any identifier the caller supplied that was *not* trusted, kept for
 *   diagnostics only. Never used to correlate. It stays a `String` because it is a wire-level fact
 *   about what the caller sent, not an identifier of ours.
 */
public data class RequestIdentity(
    val requestId: Uuid,
    val upstreamRequestId: String? = null,
)

/**
 * Generates a fresh authoritative request identifier.
 *
 * A version-7 UUID, stamped with the instant given by the [Engine]'s selected clock — the same clock
 * [com.lightningkite.lightningserver.runtime.now] reads, so a test's injected clock controls the id's
 * embedded timestamp. Because the id embeds its own mint time, the audit layer can elide a separate
 * `at` column and derive time from the id. Keep every execution-id minting site on this function so
 * that derivation stays sound and ids stay roughly time-ordered for index locality.
 */
@OptIn(ExperimentalUuidApi::class)
context(engine: Engine)
public fun generateRequestId(): Uuid = Uuid.generateV7NonMonotonicAt(engine.clock.now())

/**
 * Determines the [RequestIdentity] for an incoming request.
 *
 * A caller-supplied request ID is never authoritative. If an arbitrary client could set the ID, it
 * could forge or collide identifiers to splice its own activity into another principal's trace, or
 * to poison correlation across the access, disclosure, and audit logs — an attack on the integrity
 * of the logs themselves. An inbound ID is therefore only adopted when it arrives in
 * [trustedRequestIdHeader], which names a header stamped by a reverse proxy the deployment has
 * declared trustworthy. Leave it null and every request gets a freshly generated ID.
 *
 * Point [trustedRequestIdHeader] at `X-Request-ID` when running behind Envoy, which both stamps that
 * header and forwards the same value it records — so the proxy's capture and the server's own logs
 * share an identifier with no correlation step.
 *
 * @param onTrustedHeaderMissing Invoked when [trustedRequestIdHeader] is configured but the header is
 *   absent or does not hold a UUID, which means the request did not arrive through the expected
 *   proxy, or that proxy does not stamp UUIDs. A fresh ID is generated in that case, so correlation
 *   degrades rather than failing.
 */
context(engine: Engine)
public fun HttpHeaders.requestIdentity(
    trustedRequestIdHeader: String?,
    onTrustedHeaderMissing: () -> Unit = {},
): RequestIdentity {
    val claimed = get(HttpHeader.XRequestId)?.root
    if (trustedRequestIdHeader == null) return RequestIdentity(generateRequestId(), claimed)

    val trusted = get(trustedRequestIdHeader)?.root?.let {
        try {
            Uuid.parse(it)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    if (trusted == null) {
        onTrustedHeaderMissing()
        return RequestIdentity(generateRequestId(), claimed)
    }
    // When the trusted header IS X-Request-ID the claimed value is the trusted one, so there is no
    // separate untrusted claim worth recording.
    val upstream = claimed.takeUnless { trustedRequestIdHeader.equals(HttpHeader.XRequestId, ignoreCase = true) }
    return RequestIdentity(trusted, upstream)
}
