package com.lightningkite.lightningserver.http

import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * The identifiers correlating one logical request across every log the server writes.
 *
 * @property requestId The authoritative identifier. Always server-controlled: either generated here
 *   or adopted from a reverse proxy the deployment has explicitly declared trustworthy.
 * @property upstreamRequestId Any identifier the caller supplied that was *not* trusted, kept for
 *   diagnostics only. Never used to correlate.
 */
public data class RequestIdentity(
    val requestId: String,
    val upstreamRequestId: String? = null,
)

/** Generates a fresh authoritative request identifier. */
public fun generateRequestId(): String {
    val ba = ByteArray(16)
    SecureRandom.getInstanceStrong().nextBytes(ba)
    return Base64.UrlSafe.encode(ba)
}

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
 *   absent, which means the request did not arrive through the expected proxy. A fresh ID is
 *   generated in that case, so correlation degrades rather than failing.
 */
public fun HttpHeaders.requestIdentity(
    trustedRequestIdHeader: String?,
    onTrustedHeaderMissing: () -> Unit = {},
): RequestIdentity {
    val claimed = get(HttpHeader.XRequestId)?.root
    if (trustedRequestIdHeader == null) return RequestIdentity(generateRequestId(), claimed)

    val trusted = get(trustedRequestIdHeader)?.root
    if (trusted == null) {
        onTrustedHeaderMissing()
        return RequestIdentity(generateRequestId(), claimed)
    }
    // When the trusted header IS X-Request-ID the claimed value is the trusted one, so there is no
    // separate untrusted claim worth recording.
    val upstream = claimed.takeUnless { trustedRequestIdHeader.equals(HttpHeader.XRequestId, ignoreCase = true) }
    return RequestIdentity(trusted, upstream)
}
