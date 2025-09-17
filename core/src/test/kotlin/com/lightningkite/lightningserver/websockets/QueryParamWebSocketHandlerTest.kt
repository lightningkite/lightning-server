package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.data.set
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.deprecations.websocket
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.runtime.test.test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QueryParamWebSocketHandlerTest {

    object TestServer : ServerBuilder() {
        val broadcast = path.path("broadcast").topic(String.serializer())

        // Used to observe which path and params the underlying handler actually received
        @Volatile var lastRequest: WebSocketConnectRequest<*>? = null

        val mirror = path.path("mirror") bind WebSocketHandler(
            storageSerializer = Unit.serializer(),
            willConnect = { Unit },
            didConnect = {
                TestServer.lastRequest = request
                subscribe(broadcast)
            },
            topicHandlers = { broadcast bind { send(WebSocketFrame(it.value)) } },
            messageFromClient = { frame ->
                // Touch cache when requested to verify finalize() writes back updated request
                if (frame is WebSocketFrame.Text && frame.text == "cache") {
                    request[CacheKey] = "yes"
                }
                send(frame)
            },
            disconnect = {}
        )

        val other = path.path("other") bind WebSocketHandler(
            storageSerializer = Unit.serializer(),
            willConnect = { Unit },
            didConnect = { TestServer.lastRequest = request },
            topicHandlers = { },
            messageFromClient = { frame ->
                if (frame is WebSocketFrame.Text) send(WebSocketFrame("other:" + frame.text))
                else send(frame)
            },
            disconnect = {}
        )

        val qp = path.path("qp") bind QueryParamWebSocketHandler()

        object CacheKey : com.lightningkite.lightningserver.data.SerializableCache.Key<String> {
            override val id: String = "qp-test-cache"
            override val serializer = String.serializer()
        }
    }

    @Test
    fun routes_by_query_param_and_fixes_inner_query(): Unit = runBlocking {
        TestServer.test(settings = { }) {
            val ws = TestServer.qp.test(
                queryParameters = listOf(
                    "path" to "/mirror?foo=1",
                    "extra" to "z"
                )
            )
            // Underlying should have received a request to /mirror and extracted foo=1 from the path query
            val seen = TestServer.lastRequest!!
            assertEquals("/mirror", seen.path.string)
            val params = seen.queryParameters.toMap()
            // "path" should be stripped; inner foo should be present, and extra preserved
            check("path" !in params)
            assertEquals("1", params["foo"])
            assertEquals("z", params["extra"])

            var last: WebSocketFrame? = null
            ws.onMessageSent = { last = it }
            ws.send(WebSocketFrame.Text("ping"))
            assertEquals(WebSocketFrame.Text("ping"), last)
            ws.close()
        }
    }

    @Test
    fun header_x_path_has_precedence_over_query_param(): Unit = runBlocking {
        TestServer.test(settings = { }) {
            val ws = TestServer.qp.test(
                queryParameters = listOf("path" to "/bad-please-never-use"),
                headers = HttpHeaders("x-path" to "/other?bar=2")
            )
            val seen = TestServer.lastRequest!!
            // Routed to /other per x-path header
            assertEquals("/other", seen.path.string)
            val params = seen.queryParameters.toMap()
            // Ensure header-provided query param made it through
            assertEquals("2", params["bar"])

            var last: WebSocketFrame? = null
            ws.onMessageSent = { last = it }
            ws.send(WebSocketFrame.Text("x"))
            assertEquals(WebSocketFrame.Text("other:x"), last)
            ws.close()
        }
    }

    @Test
    fun not_found_when_no_matching_handler(): Unit = runBlocking {
        TestServer.test(settings = { }) {
            assertFailsWith<NotFoundException> {
                // No handler bound at /missing
                TestServer.qp.test(queryParameters = listOf("path" to "/missing"))
            }
        }
    }

    @Test
    fun cache_update_is_persisted_back_to_outer_state(): Unit = runBlocking {
        TestServer.test(settings = { }) {
            val ws = TestServer.qp.test(queryParameters = listOf("path" to "/mirror"))
            // Trigger cache write in underlying handler
            ws.send(WebSocketFrame.Text("cache"))
            // After the message finishes, finalize() should have propagated the updated request back
            val outer = ws.currentState as QueryParamWebSocketHandlerData
            val value = with(ws.server) { outer.request.cache[TestServer.CacheKey] }
            assertEquals("yes", value)
            ws.close()
        }
    }
}
