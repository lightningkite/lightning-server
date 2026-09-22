@file:OptIn(InternalLightningServerApi::class, com.lightningkite.services.data.Unsafe::class)

package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.Engine
import com.lightningkite.lightningserver.runtime.EngineBase
import com.lightningkite.lightningserver.runtime.createRuntime
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.services.data.UuidV7
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val outerId = Execution.ID(UuidV7.fromRaw(Uuid.parse("00000000-0000-4000-8000-0000000000d4")))

private fun outer() = Execution.Http(
    id = outerId,
    endpoint = RawHttpEndpoint(asString = "/outer", method = HttpMethod.POST),
)

private fun subTestRequest(path: String): HttpRequest<PathSpec> = HttpRequest(
    path = RawHttpEndpoint(asString = path, method = HttpMethod.GET),
    queryParameters = QueryParameters.EMPTY,
    headers = HttpHeaders.EMPTY,
    domain = "example.com",
    protocol = "https",
    sourceIp = "local",
)

private val subRecorded = mutableListOf<Execution.Http>()

/**
 * A sub-request's parentage is no longer derived by a standalone `subRequest` helper; it's derived
 * inline by [com.lightningkite.lightningserver.runtime.handle] (the same seam `/meta/bulk`
 * dispatches through), so [RequestIdentityTest]'s sub-request tests exercise it end to end: each
 * endpoint records its own [Execution.Http], and `nested` dispatches a further sub-request from
 * inside its own execution to prove the parent follows the nesting rather than always naming the
 * outermost caller.
 */
private object SubServer : ServerBuilder() {
    val a = path.path("a").get bind HttpHandler {
        subRecorded += serverRuntime.execution as Execution.Http
        HttpResponse.plainText("ok")
    }
    val b = path.path("b").get bind HttpHandler {
        subRecorded += serverRuntime.execution as Execution.Http
        HttpResponse.plainText("ok")
    }
    val nested = path.path("nested").get bind HttpHandler {
        subRecorded += serverRuntime.execution as Execution.Http
        serverRuntime.handle(subTestRequest("b"))
        HttpResponse.plainText("ok")
    }
}

class RequestIdentityTest {

    private val engine: Engine = object : EngineBase(EmptyServer.build()) {
        override val serverId: String = "bare"
        override val serverVersion: String = "test"

        override suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(
            event: WebSocketSubscriptionMessage<PATH, T>,
        ): Nothing = throw NotImplementedError()

        override suspend fun <T> dispatchTask(task: Task<T>, input: T, from: Execution): Nothing =
            throw NotImplementedError()
    }

    private object EmptyServer : ServerBuilder()

    /** The same bare engine, but with a clock a test controls. */
    private fun engineAt(instant: kotlin.time.Instant): Engine = object : EngineBase(EmptyServer.build()) {
        override val serverId: String = "bare"
        override val serverVersion: String = "test"
        override val clock: kotlin.time.Clock = object : kotlin.time.Clock {
            override fun now(): kotlin.time.Instant = instant
        }

        override suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(
            event: WebSocketSubscriptionMessage<PATH, T>,
        ): Nothing = throw NotImplementedError()

        override suspend fun <T> dispatchTask(task: Task<T>, input: T, from: Execution): Nothing =
            throw NotImplementedError()
    }

    /**
     * Reads back the two things a version-7 UUID promises, the way a consumer has to: the version
     * nibble and the 48-bit big-endian millisecond timestamp.
     *
     * Deliberately decoded here rather than reusing the audit module's helper — the point of these
     * tests is that the minting site satisfies an *independent* reader, and sharing the decoder
     * would let a wrong shared assumption pass both halves.
     */
    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    private fun Uuid.versionAndMillis(): Pair<Int, Long> =
        toLongs { msb, _ -> ((msb shr 12) and 0xF).toInt() to (msb ushr 16) }

    /** Unwraps back to the raw [Uuid] these tests compare against — reading [Execution.ID] is free, per its docs. */
    private val Execution.ID.uuid: Uuid get() = raw.raw

    /**
     * `generateRequestId`'s documentation promises that "a test's injected clock controls the id's
     * embedded timestamp", and the audit layer takes that promise seriously enough to have no `at`
     * column at all — it derives every record's time from the id.
     *
     * Nothing tied those two halves together. Both are individually tested: `RequestRecordTest`
     * covers the derivation by calling `Uuid.generateV7NonMonotonicAt` directly, and the tests above
     * cover the minting site by asserting only that ids are non-nil and unique — which is equally
     * true of `Uuid.random()`. Mutation testing confirmed the consequence: replacing this call with
     * `Uuid.random()` left all 836 core tests green, while collapsing every audit row's derived
     * timestamp to the Unix epoch, because the audit decoder returns 0 for a non-v7 id.
     */
    @Test
    fun `a generated request id carries the engine clock's instant, not the wall clock`() {
        val minted = kotlin.time.Instant.fromEpochMilliseconds(1_700_000_000_123)

        val (version, millis) = with(engineAt(minted)) { Execution.ID.generate() }.uuid.versionAndMillis()

        assertEquals(7, version, "the audit layer's timestamp decoder returns 0 for any non-v7 id")
        assertEquals(
            minted.toEpochMilliseconds(),
            millis,
            "the id's embedded timestamp must come from the engine's clock, or an injected clock " +
                "does not control what the audit log records",
        )
    }

    /** The whole request-identity path, not just the generator, has to respect the clock. */
    @Test
    fun `a request identity's generated id also carries the engine clock's instant`() {
        val minted = kotlin.time.Instant.fromEpochMilliseconds(1_700_000_000_456)

        val identity = with(engineAt(minted)) { headers().requestIdentity(trustedRequestIdHeader = null) }

        assertEquals(minted.toEpochMilliseconds(), identity.requestId.uuid.versionAndMillis().second)
    }

    private fun headers(vararg pairs: Pair<String, String>) = HttpHeaders(pairs.toList())

    @Test
    fun `generates a fresh id when no trusted header is configured`() {
        val identity = with(engine) { headers().requestIdentity(trustedRequestIdHeader = null) }
        assertNotEquals(Uuid.NIL, identity.requestId.uuid)
        assertNull(identity.upstreamRequestId)
    }

    @Test
    fun `generated ids are unique`() {
        val ids = (1..100).map { with(engine) { headers().requestIdentity(null).requestId } }
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
        val identity = with(engine) {
            headers(HttpHeader.XRequestId to claimed.toString())
                .requestIdentity(trustedRequestIdHeader = null)
        }

        assertNotEquals(claimed, identity.requestId.uuid)
        assertEquals(claimed.toString(), identity.upstreamRequestId)
    }

    @Test
    fun `a client supplied id is not adopted when the trusted header is a different header`() {
        val claimed = Uuid.parse("00000000-0000-4000-8000-0000000000a1")
        val identity = with(engine) {
            headers(HttpHeader.XRequestId to claimed.toString())
                .requestIdentity(trustedRequestIdHeader = "X-Proxy-Request-Id")
        }

        assertNotEquals(claimed, identity.requestId.uuid)
        assertEquals(claimed.toString(), identity.upstreamRequestId)
    }

    @Test
    fun `adopts the id from the configured trusted header`() {
        val proxyId = Uuid.parse("00000000-0000-4000-8000-0000000000b2")
        val identity = with(engine) {
            headers("X-Proxy-Request-Id" to proxyId.toString())
                .requestIdentity(trustedRequestIdHeader = "X-Proxy-Request-Id")
        }

        assertEquals(proxyId, identity.requestId.uuid)
    }

    @Test
    fun `records a separate untrusted claim alongside the trusted id`() {
        val proxyId = Uuid.parse("00000000-0000-4000-8000-0000000000b2")
        val identity = with(engine) {
            headers(
                "X-Proxy-Request-Id" to proxyId.toString(),
                HttpHeader.XRequestId to "attacker-chosen",
            ).requestIdentity(trustedRequestIdHeader = "X-Proxy-Request-Id")
        }

        assertEquals(proxyId, identity.requestId.uuid)
        assertEquals("attacker-chosen", identity.upstreamRequestId)
    }

    /** The Envoy arrangement: the proxy stamps X-Request-ID, so there is no separate untrusted claim. */
    @Test
    fun `no upstream id is recorded when the trusted header is X-Request-ID itself`() {
        val proxyId = Uuid.parse("00000000-0000-4000-8000-0000000000c3")
        val identity = with(engine) {
            headers(HttpHeader.XRequestId to proxyId.toString())
                .requestIdentity(trustedRequestIdHeader = HttpHeader.XRequestId)
        }

        assertEquals(proxyId, identity.requestId.uuid)
        assertNull(identity.upstreamRequestId)
    }

    @Test
    fun `header matching is case insensitive`() {
        val proxyId = Uuid.parse("00000000-0000-4000-8000-0000000000c3")
        val identity = with(engine) {
            headers("x-request-id" to proxyId.toString())
                .requestIdentity(trustedRequestIdHeader = "X-Request-ID")
        }

        assertEquals(proxyId, identity.requestId.uuid)
        assertNull(identity.upstreamRequestId)
    }

    /** Degrade to a generated id rather than failing, but make the misconfiguration observable. */
    @Test
    fun `generates an id and reports when the configured trusted header is absent`() {
        var warned = false
        val identity = with(engine) {
            headers(HttpHeader.XRequestId to "attacker-chosen")
                .requestIdentity(trustedRequestIdHeader = "X-Proxy-Request-Id") { warned = true }
        }

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
        val identity = with(engine) {
            headers("X-Proxy-Request-Id" to "not-a-uuid")
                .requestIdentity(trustedRequestIdHeader = "X-Proxy-Request-Id") { warned = true }
        }

        assertTrue(warned)
        assertNotEquals(Uuid.NIL, identity.requestId.uuid)
    }

    @Test
    fun `a sub-request gets its own id parented to the outer request`() {
        subRecorded.clear()
        SubServer.test(settings = {}) {
            runBlocking {
                with(serverRuntime.createRuntime(outer())) { serverRuntime.handle(subTestRequest("a")) }
            }
        }

        val sub = subRecorded.single()
        assertNotEquals(outerId, sub.id)
        assertEquals(outerId, sub.causedBy)
        assertEquals(outerId, sub.rootExecution)
    }

    @Test
    fun `sibling sub-requests get distinct ids and share a parent`() {
        subRecorded.clear()
        SubServer.test(settings = {}) {
            runBlocking {
                with(serverRuntime.createRuntime(outer())) {
                    serverRuntime.handle(subTestRequest("a"))
                    serverRuntime.handle(subTestRequest("b"))
                }
            }
        }

        val (a, b) = subRecorded
        assertNotEquals(a.id, b.id)
        assertEquals(outerId, a.causedBy)
        assertEquals(outerId, b.causedBy)
    }

    @Test
    fun `nesting keeps the root while the parent follows the nesting`() {
        subRecorded.clear()
        SubServer.test(settings = {}) {
            runBlocking {
                with(serverRuntime.createRuntime(outer())) { serverRuntime.handle(subTestRequest("nested")) }
            }
        }

        val (nested, inner) = subRecorded
        assertEquals(outerId, nested.causedBy)
        assertEquals(nested.id, inner.causedBy)
        assertEquals(outerId, inner.rootExecution)
        assertNotEquals(outerId, inner.causedBy)
    }
}
