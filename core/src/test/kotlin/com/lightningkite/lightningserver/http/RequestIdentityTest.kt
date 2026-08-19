package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestIdentityTest {

    private fun headers(vararg pairs: Pair<String, String>) = HttpHeaders(pairs.toList())

    @Test
    fun `generates a fresh id when no trusted header is configured`() {
        val identity = headers().requestIdentity(trustedRequestIdHeader = null)
        assertTrue(identity.requestId.isNotBlank())
        assertNull(identity.upstreamRequestId)
    }

    @Test
    fun `generated ids are unique`() {
        val ids = (1..100).map { headers().requestIdentity(null).requestId }
        assertEquals(100, ids.toSet().size)
    }

    /**
     * The security property this whole mechanism exists for: a caller must never be able to choose
     * the identifier its activity is recorded under, or it could splice itself into another
     * principal's trace.
     */
    @Test
    fun `a client supplied id is never adopted when no trusted header is configured`() {
        val identity = headers(HttpHeader.XRequestId to "attacker-chosen")
            .requestIdentity(trustedRequestIdHeader = null)

        assertNotEquals("attacker-chosen", identity.requestId)
        assertEquals("attacker-chosen", identity.upstreamRequestId)
    }

    @Test
    fun `a client supplied id is not adopted when the trusted header is a different header`() {
        val identity = headers(HttpHeader.XRequestId to "attacker-chosen")
            .requestIdentity(trustedRequestIdHeader = "X-Proxy-Request-Id")

        assertNotEquals("attacker-chosen", identity.requestId)
        assertEquals("attacker-chosen", identity.upstreamRequestId)
    }

    @Test
    fun `adopts the id from the configured trusted header`() {
        val identity = headers("X-Proxy-Request-Id" to "proxy-123")
            .requestIdentity(trustedRequestIdHeader = "X-Proxy-Request-Id")

        assertEquals("proxy-123", identity.requestId)
    }

    @Test
    fun `records a separate untrusted claim alongside the trusted id`() {
        val identity = headers(
            "X-Proxy-Request-Id" to "proxy-123",
            HttpHeader.XRequestId to "attacker-chosen",
        ).requestIdentity(trustedRequestIdHeader = "X-Proxy-Request-Id")

        assertEquals("proxy-123", identity.requestId)
        assertEquals("attacker-chosen", identity.upstreamRequestId)
    }

    /** The Envoy arrangement: the proxy stamps X-Request-ID, so there is no separate untrusted claim. */
    @Test
    fun `no upstream id is recorded when the trusted header is X-Request-ID itself`() {
        val identity = headers(HttpHeader.XRequestId to "envoy-abc")
            .requestIdentity(trustedRequestIdHeader = HttpHeader.XRequestId)

        assertEquals("envoy-abc", identity.requestId)
        assertNull(identity.upstreamRequestId)
    }

    @Test
    fun `header matching is case insensitive`() {
        val identity = headers("x-request-id" to "envoy-abc")
            .requestIdentity(trustedRequestIdHeader = "X-Request-ID")

        assertEquals("envoy-abc", identity.requestId)
        assertNull(identity.upstreamRequestId)
    }

    /** Degrade to a generated id rather than failing, but make the misconfiguration observable. */
    @Test
    fun `generates an id and reports when the configured trusted header is absent`() {
        var warned = false
        val identity = headers(HttpHeader.XRequestId to "attacker-chosen")
            .requestIdentity(trustedRequestIdHeader = "X-Proxy-Request-Id") { warned = true }

        assertTrue(warned)
        assertNotEquals("attacker-chosen", identity.requestId)
        assertEquals("attacker-chosen", identity.upstreamRequestId)
    }

    private fun request(id: String) = HttpRequest<PathSpec>(
        path = RawHttpEndpoint(asString = "/outer", method = HttpMethod.POST),
        queryParameters = QueryParameters.EMPTY,
        headers = HttpHeaders.EMPTY,
        domain = "example.com",
        protocol = "https",
        sourceIp = "local",
        requestId = id,
    )

    @Test
    fun `subRequest gets its own id parented to the outer request`() {
        val outer = request("outer-id")
        val sub = outer.subRequest<PathSpec>(RawHttpEndpoint(asString = "/inner", method = HttpMethod.GET))

        assertNotEquals(outer.requestId, sub.requestId)
        assertEquals("outer-id", sub.parentRequestId)
    }

    @Test
    fun `sibling sub-requests get distinct ids and share a parent`() {
        val outer = request("outer-id")
        val a = outer.subRequest<PathSpec>(RawHttpEndpoint(asString = "/a", method = HttpMethod.GET))
        val b = outer.subRequest<PathSpec>(RawHttpEndpoint(asString = "/b", method = HttpMethod.GET))

        assertNotEquals(a.requestId, b.requestId)
        assertEquals("outer-id", a.parentRequestId)
        assertEquals("outer-id", b.parentRequestId)
    }

    @Test
    fun `copyWithNewPathType preserves identity`() {
        val outer = request("outer-id")
        val copied = outer.copyWithNewPathType<PathSpec>(
            RawHttpEndpoint(asString = "/elsewhere", method = HttpMethod.GET)
        )

        assertEquals("outer-id", copied.requestId)
        assertNull(copied.parentRequestId)
    }
}
