package com.lightningkite.lightningserver.sessions

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.auth.AccessLogInterceptor
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.uuid.Uuid

/**
 * The access log previously emitted before the handler ran, so a line carried no status, no duration,
 * and looked identical whether the request succeeded or died mid-handler. It also implemented only
 * the HTTP interceptor interface, so WebSocket connections — a long-lived disclosure channel —
 * produced no access-log lines at all.
 */
class AccessLogInterceptorTest {

    private object TestServer : ServerBuilder() {
        init {
            registerBasicMediaTypeCoders()
            install(AccessLogInterceptor())
        }

        val ok = path.path("ok").get bind HttpHandler<PathSpec0> { HttpResponse.plainText("fine") }
        val boom = path.path("boom").get bind HttpHandler<PathSpec0> { error("deliberate failure") }
        val socket = path.path("socket") bind WebSocketHandler(
            storageSerializer = Unit.serializer(),
            willConnect = { Unit },
            didConnect = {},
            topicHandlers = {},
            messageFromClient = {},
            disconnect = {},
        )
    }

    /** Captures the framework logger for the duration of [block], then detaches. */
    private fun capturing(block: () -> Unit): List<String> {
        val logbackLogger = LoggerFactory.getLogger("com.lightningkite.lightningserver") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = logbackLogger.level
        logbackLogger.level = Level.INFO
        logbackLogger.addAppender(appender)
        try {
            block()
        } finally {
            logbackLogger.detachAppender(appender)
            logbackLogger.level = previousLevel
        }
        return appender.list.map { it.formattedMessage }
    }

    private fun accessLines(lines: List<String>) = lines.filter { it.contains("accessed by") || it.startsWith("ws ") }

    private val requestIdUnderTest = Uuid.parse("11111111-2222-3333-4444-555555555555")

    private fun get(path: String) = HttpRequest<PathSpec>(
        path = RawHttpEndpoint(asString = path, method = HttpMethod.GET),
        queryParameters = QueryParameters.EMPTY,
        headers = HttpHeaders.EMPTY,
        domain = "example.com",
        protocol = "https",
        sourceIp = "10.0.0.1",
        requestId = requestIdUnderTest,
    )

    @Test
    fun `an http line carries the outcome and the request id`() {
        var lines: List<String> = emptyList()
        TestServer.test(settings = {}) {
            lines = capturing { runBlocking { serverRuntime.handle(get("/ok")) } }
        }
        val line = accessLines(lines).singleOrNull() ?: fail("expected one access line; got $lines")
        assertTrue(line.contains("-> 200"), "line should carry the status; was: $line")
        assertTrue(Regex("in \\d+ms").containsMatchIn(line), "line should carry the duration; was: $line")
        assertTrue(line.contains(requestIdUnderTest.toString()), "line should carry the request id; was: $line")
        assertTrue(line.contains("10.0.0.1"), "line should carry the source ip; was: $line")
    }

    /** The case that previously logged a clean-looking line: the request never actually succeeded. */
    @Test
    fun `a request that dies in the handler still produces a line, marked failed`() {
        var lines: List<String> = emptyList()
        TestServer.test(settings = {}) {
            lines = capturing {
                runBlocking { runCatching { serverRuntime.handle(get("/boom")) } }
            }
        }
        val line = accessLines(lines).singleOrNull() ?: fail("expected one access line; got $lines")
        assertTrue(
            line.contains("-> 500") || line.contains("-> failed"),
            "a failed request must not look like a successful one; was: $line",
        )
    }

    @Test
    fun `a webSocket connection is logged at open and close`() {
        var lines: List<String> = emptyList()
        TestServer.test(settings = {}) {
            lines = capturing {
                runBlocking {
                    val ws = TestServer.socket.test()
                    ws.close()
                }
            }
        }
        val ws = accessLines(lines).filter { it.startsWith("ws ") }
        assertEquals(2, ws.size, "expected an open and a close line; got $ws")
        assertTrue(ws.any { it.contains("opened by") }, "missing the open line; got $ws")
        assertTrue(ws.any { it.contains("closed by") && it.contains("NORMAL") }, "missing the close line; got $ws")
    }
}
