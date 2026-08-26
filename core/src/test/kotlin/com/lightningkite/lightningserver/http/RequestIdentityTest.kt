package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class RequestIdentityTest {

    private fun headers(vararg pairs: Pair<String, String>) = HttpHeaders(pairs.toList())

    @Test
    fun `generates a fresh id when no trusted header is configured`() {
        val identity = headers().requestIdentity(trustedRequestIdHeader = null)
        assertNotEquals(Uuid.NIL, identity.requestId)
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
        val claimed = Uuid.parse("00000000-0000-4000-8000-0000000000a1")
        val identity = headers(HttpHeader.XRequestId to claimed.toString())
            .requestIdentity(trustedRequestIdHeader = null)

        assertNotEquals(claimed, identity.requestId)
        assertEquals(claimed.toString(), identity.upstreamRequestId)
    }

    @Test
    fun `a client supplied id is not adopted when the trusted header is a different header`() {
        val claimed = Uuid.parse("00000000-0000-4000-8000-0000000000a1")
        val identity = headers(HttpHeader.XRequestId to claimed.toString())
            .requestIdentity(trustedRequestIdHeader = "X-Proxy-Request-Id")

        assertNotEquals(claimed, identity.requestId)
        assertEquals(claimed.toString(), identity.upstreamRequestId)
    }

    @Test
    fun `adopts the id from the configured trusted header`() {
        val proxyId = Uuid.parse("00000000-0000-4000-8000-0000000000b2")
        val identity = headers("X-Proxy-Request-Id" to proxyId.toString())
            .requestIdentity(trustedRequestIdHeader = "X-Proxy-Request-Id")

        assertEquals(proxyId, identity.requestId)
    }

    @Test
    fun `records a separate untrusted claim alongside the trusted id`() {
        val proxyId = Uuid.parse("00000000-0000-4000-8000-0000000000b2")
        val identity = headers(
            "X-Proxy-Request-Id" to proxyId.toString(),
            HttpHeader.XRequestId to "attacker-chosen",
        ).requestIdentity(trustedRequestIdHeader = "X-Proxy-Request-Id")

        assertEquals(proxyId, identity.requestId)
        assertEquals("attacker-chosen", identity.upstreamRequestId)
    }

    /** The Envoy arrangement: the proxy stamps X-Request-ID, so there is no separate untrusted claim. */
    @Test
    fun `no upstream id is recorded when the trusted header is X-Request-ID itself`() {
        val proxyId = Uuid.parse("00000000-0000-4000-8000-0000000000c3")
        val identity = headers(HttpHeader.XRequestId to proxyId.toString())
            .requestIdentity(trustedRequestIdHeader = HttpHeader.XRequestId)

        assertEquals(proxyId, identity.requestId)
        assertNull(identity.upstreamRequestId)
    }

    @Test
    fun `header matching is case insensitive`() {
        val proxyId = Uuid.parse("00000000-0000-4000-8000-0000000000c3")
        val identity = headers("x-request-id" to proxyId.toString())
            .requestIdentity(trustedRequestIdHeader = "X-Request-ID")

        assertEquals(proxyId, identity.requestId)
        assertNull(identity.upstreamRequestId)
    }

    /** Degrade to a generated id rather than failing, but make the misconfiguration observable. */
    @Test
    fun `generates an id and reports when the configured trusted header is absent`() {
        var warned = false
        val identity = headers(HttpHeader.XRequestId to "attacker-chosen")
            .requestIdentity(trustedRequestIdHeader = "X-Proxy-Request-Id") { warned = true }

        assertTrue(warned)
        assertEquals("attacker-chosen", identity.upstreamRequestId)
    }

    /**
     * A proxy that stamps something other than a UUID is a misconfiguration of the same kind as one
     * that stamps nothing, and is handled the same way: correlation degrades, the request does not.
     */
    @Test
    fun `generates an id and reports when the trusted header does not hold a UUID`() {
        var warned = false
        val identity = headers("X-Proxy-Request-Id" to "not-a-uuid")
            .requestIdentity(trustedRequestIdHeader = "X-Proxy-Request-Id") { warned = true }

        assertTrue(warned)
        assertNotEquals(Uuid.NIL, identity.requestId)
    }

    private fun request(id: Uuid) = HttpRequest<PathSpec>(
        path = RawHttpEndpoint(asString = "/outer", method = HttpMethod.POST),
        queryParameters = QueryParameters.EMPTY,
        headers = HttpHeaders.EMPTY,
        domain = "example.com",
        protocol = "https",
        sourceIp = "local",
        requestId = id,
    )

    private val outerId = Uuid.parse("00000000-0000-4000-8000-0000000000d4")

    @Test
    fun `subRequest gets its own id parented to the outer request`() {
        val outer = request(outerId)
        val sub = outer.subRequest<PathSpec>(RawHttpEndpoint(asString = "/inner", method = HttpMethod.GET))

        assertNotEquals(outer.requestId, sub.requestId)
        assertEquals(outerId, sub.parentRequestId)
    }

    @Test
    fun `sibling sub-requests get distinct ids and share a parent`() {
        val outer = request(outerId)
        val a = outer.subRequest<PathSpec>(RawHttpEndpoint(asString = "/a", method = HttpMethod.GET))
        val b = outer.subRequest<PathSpec>(RawHttpEndpoint(asString = "/b", method = HttpMethod.GET))

        assertNotEquals(a.requestId, b.requestId)
        assertEquals(outerId, a.parentRequestId)
        assertEquals(outerId, b.parentRequestId)
    }

    @Test
    fun `copyWithNewPathType preserves identity`() {
        val outer = request(outerId)
        val copied = outer.copyWithNewPathType<PathSpec>(
            RawHttpEndpoint(asString = "/elsewhere", method = HttpMethod.GET)
        )

        assertEquals(outerId, copied.requestId)
        assertNull(copied.parentRequestId)
    }
}
