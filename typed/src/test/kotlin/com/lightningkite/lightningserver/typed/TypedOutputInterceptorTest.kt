package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.definition.GeneralServerSettings
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typedoutput.TypedOutputInterceptor
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The disclosure log's entire premise is that no typed value reaches a client unobserved. These
 * tests pin that: an ordinary response, every sub-response of a multiplexed request, and a
 * WebSocket push all arrive at the interceptor, and an interceptor that fails stops the send rather
 * than trailing it.
 */
class TypedOutputInterceptorTest {

    private data class Seen(
        val requestId: Uuid,
        val parentRequestId: Uuid?,
        val serialName: String,
        val value: Any?,
    )

    private object Observed {
        val seen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())

        /** Set to fail the next observation, standing in for an audit sink that cannot write. */
        @Volatile
        var failOn: String? = null

        fun reset() {
            seen.clear()
            failOn = null
        }
    }

    private class Recorder : TypedOutputInterceptor {
        override val name: String = "Recorder"

        context(runtime: ServerRuntime)
        override suspend fun <T> outputProduced(request: Request<*>, serializer: KSerializer<T>, value: T) {
            Observed.seen.add(
                Seen(request.requestId, request.parentRequestId, serializer.descriptor.serialName, value)
            )
            if (Observed.failOn != null && Observed.failOn == value) {
                throw IllegalStateException("audit sink unavailable")
            }
        }
    }

    object TestServer : ServerBuilder() {
        init {
            registerBasicMediaTypeCoders()
            install(Recorder())
        }

        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())

        val alpha = path.path("alpha").get bind ApiHttpHandler(
            summary = "Alpha",
            auth = noAuth,
            implementation = { _: Unit -> "alpha" }
        )

        val beta = path.path("beta").get bind ApiHttpHandler(
            summary = "Beta",
            auth = noAuth,
            implementation = { _: Unit -> "beta" }
        )

        val info = database.modelInfo<HasId<*>?, Sample, String>(
            tableName = "Sample",
            auth = noAuth,
            permissions = { ModelPermissions.allowAll() }
        )
        val updates = path.path("sample").path("updates") include ModelRestUpdatesWebSocket(info)

        val meta = path.path("meta") include MetaEndpoints(
            packageName = "com.lightningkite.lightningserver.typed",
            database = database,
            cache = cache,
        )
    }

    private val outerRequestId = Uuid.parse("00000000-0000-4000-8000-000000000001")

    private fun request(path: String, method: HttpMethod = HttpMethod.GET, body: String? = null) =
        HttpRequest<PathSpec>(
            path = RawHttpEndpoint(asString = path, method = method),
            queryParameters = QueryParameters.EMPTY,
            headers = HttpHeaders.EMPTY,
            domain = "example.com",
            protocol = "https",
            sourceIp = "local",
            requestId = outerRequestId,
            body = body?.let { TypedData.text(it, MediaType.Application.Json) },
        )

    private fun onServer(block: suspend context(ServerRuntime) () -> Unit) = TestServer.test(settings = {
        generalSettings set GeneralServerSettings()
        database set Database.Settings()
    }) {
        Observed.reset()
        runBlocking { block(serverRuntime) }
    }

    @Test
    fun `an http response is observed with its serializer and value`() = onServer {
        serverRuntime.handle(request("/alpha"))

        assertEquals(1, Observed.seen.size, "expected exactly one observation, saw ${Observed.seen}")
        val seen = Observed.seen.single()
        assertEquals("alpha", seen.value)
        assertEquals(outerRequestId, seen.requestId)
        assertTrue(seen.serialName.contains("String"), "expected the output serializer; was ${seen.serialName}")
    }

    /**
     * The bulk endpoint carries many logical requests inside one physical one. Observing only the
     * outer request would record a single disclosure no matter how much data left the server.
     */
    @Test
    fun `every sub-response of a multiplexed request is observed separately`() = onServer {
        serverRuntime.handle(
            request(
                "/meta/bulk",
                HttpMethod.POST,
                """{"a":{"path":"/alpha","method":"GET"},"b":{"path":"/beta","method":"GET"}}""",
            )
        )

        val values = Observed.seen.map { it.value }
        assertTrue("alpha" in values, "sub-response from /alpha was not observed; saw $values")
        assertTrue("beta" in values, "sub-response from /beta was not observed; saw $values")

        val subs = Observed.seen.filter { it.value == "alpha" || it.value == "beta" }
        subs.forEach { assertEquals(outerRequestId, it.parentRequestId, "sub-response lost its parent") }
        assertEquals(2, subs.map { it.requestId }.toSet().size, "sub-responses must be separately attributable")
    }

    /**
     * Fail-closed: a disclosure that could not be recorded must not happen. The interceptor runs
     * before serialization precisely so that throwing there prevents the body from being built.
     */
    @Test
    fun `a failing interceptor prevents the response`() = onServer {
        Observed.failOn = "alpha"
        val response = serverRuntime.handle(request("/alpha"))

        assertEquals(HttpStatus.InternalServerError, response.status, "the response was sent anyway")
    }

    @Test
    fun `a websocket push is observed`() = runBlocking {
        TestServer.test(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            Observed.reset()
            val socket = TestServer.updates.webSocket.test()
            val json = socket.server.externalSerialization.json
            val always: Condition<Sample> = Condition.Always
            socket.send(WebSocketFrame.Text(json.encodeToString(Condition.serializer(Sample.serializer()), always)))

            val pushed = Observed.seen.filter { it.serialName.contains("CollectionUpdates") }
            assertTrue(
                pushed.isNotEmpty(),
                "no websocket push was observed; saw ${Observed.seen.map { it.serialName }}",
            )
        }
    }
}
