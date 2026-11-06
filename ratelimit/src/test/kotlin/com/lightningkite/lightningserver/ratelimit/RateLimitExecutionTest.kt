package com.lightningkite.lightningserver.ratelimit

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.MultiplexMessage
import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.handleWithMetrics
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.runtime.engine
import com.lightningkite.lightningserver.websockets.MultiplexWebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.text
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.cache.Cache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Checks which executions the limiter charges, through a real server: with these limits each request to
 * `/work` costs 50 s of a 100 s budget while the clock moves 5 s, so the fourth one in a row is rejected.
 */
class RateLimitExecutionTest {

    private var time: Instant = Instant.fromEpochMilliseconds(0)
    private val clock = object : Clock {
        override fun now(): Instant = time
    }

    private inner class Server : ServerBuilder() {
        val limiterSettings = setting("limiter", RateLimitSettings())
        val cache = setting("cache", Cache.Settings("ram"))

        val work = path.path("work").get bind HttpHandler {
            time += 5.seconds
            HttpResponse.plainText("worked")
        }

        // Stands in for /meta/bulk: each part is routed as a sub-request the client asked for.
        val bulk = path.path("bulk").get bind HttpHandler { request ->
            val statuses = (1..5).map {
                serverRuntime.handle(request.subRequest(RawHttpEndpoint("work", HttpMethod.GET))).status.code
            }
            HttpResponse.plainText(statuses.joinToString(","))
        }

        val smallBulk = path.path("small-bulk").get bind HttpHandler { request ->
            val statuses = (1..2).map {
                serverRuntime.handle(request.subRequest(RawHttpEndpoint("work", HttpMethod.GET))).status.code
            }
            HttpResponse.plainText(statuses.joinToString(","))
        }

        val started = CompletableDeferred<Unit>()
        val abandoned = path.path("abandoned").get bind HttpHandler {
            time += 5.seconds
            started.complete(Unit)
            awaitCancellation()
        }

        val internalCaller = path.path("internal").get bind HttpHandler { request ->
            val statuses = (1..5).map { work.handleWithMetrics(request).status.code }
            HttpResponse.plainText(statuses.joinToString(","))
        }

        // Messages take as long as a connect, so charging them would show.
        val socket = path.path("socket") bind WebSocketHandler(
            storageSerializer = Unit.serializer(),
            willConnect = { time += 5.seconds },
            didConnect = {},
            topicHandlers = {},
            messageFromClient = { time += 5.seconds },
            disconnect = {},
        )

        val multiplex = path.path("multiplex") bind MultiplexWebSocketHandler()

        init {
            registerBasicMediaTypeCoders()
            install(RateLimitInterceptor(limiterSettings, cache, exempt = listOf(bulk, smallBulk), exemptSockets = listOf(multiplex)) {
                RequestLimits(key = "caller", leeway = 100.seconds, borrowTime = 10.seconds, multiplier = 10.0, overhead = 0.seconds)
            })
        }
    }

    @Test
    fun `requests the engine received are charged and get rate-limit headers`() {
        val server = Server()
        server.test({}, { clock }) {
            runBlocking {
                val first = server.work.test()
                assertEquals(HttpStatus.OK, first.status)
                assertEquals("caller", first.headers["X-RateLimit-Identity"]?.root)
                assertEquals(HttpStatus.OK, server.work.test().status)
                assertEquals(HttpStatus.OK, server.work.test().status)
                assertEquals(HttpStatus.TooManyRequests, server.work.test().status)
            }
        }
    }

    // Claude: the fourth request starts at 15 s, before the 50 s its caller reserved up to, and its rejection costs 10 s more.
    @Test
    fun `a rejected request gets Retry-After and rate-limit headers`() {
        val server = Server()
        server.test({}, { clock }) {
            runBlocking {
                repeat(3) { server.work.test() }
                val rejected = server.work.test()
                assertEquals(HttpStatus.TooManyRequests, rejected.status)
                assertEquals("45", rejected.headers[HttpHeader.RetryAfter]?.root)
                assertEquals("caller", rejected.headers["X-RateLimit-Identity"]?.root)
                assertEquals(Instant.fromEpochSeconds(60).toString(), rejected.headers["X-RateLimit-AvailableAfter"]?.root)
            }
        }
    }

    @Test
    fun `a rejected request still gets Retry-After when rate-limit headers are off`() {
        val server = Server()
        server.test({ server.limiterSettings set RateLimitSettings(includeHeaders = false) }, { clock }) {
            runBlocking {
                repeat(3) { server.work.test() }
                val rejected = server.work.test()
                assertEquals(HttpStatus.TooManyRequests, rejected.status)
                assertEquals("45", rejected.headers[HttpHeader.RetryAfter]?.root)
                assertNull(rejected.headers["X-RateLimit-Identity"])
            }
        }
    }

    @Test
    fun `each bulk part is charged on its own and the exempt bulk request is not`() {
        val server = Server()
        server.test({}, { clock }) {
            runBlocking {
                val response = server.bulk.test()
                assertEquals("200,200,200,429,429", response.body!!.text())
            }
        }
    }

    // Claude: the two rejected parts each cost 10 s, so the caller is blocked until 70 s.
    @Test
    fun `a bulk request with rejected parts reports when the caller can continue`() {
        val server = Server()
        server.test({}, { clock }) {
            runBlocking {
                val response = server.bulk.test()
                assertEquals(HttpStatus.OK, response.status)
                assertEquals("caller", response.headers["X-RateLimit-Identity"]?.root)
                assertEquals(Instant.fromEpochSeconds(70).toString(), response.headers["X-RateLimit-AvailableAfter"]?.root)
                assertNotNull(response.headers["X-RateLimit-RemainingTime"])
                assertNull(response.headers[HttpHeader.RetryAfter])
            }
        }
    }

    // Claude: the first part reserves up to -90 s and the second, after the first's refund, up to -40 s.
    @Test
    fun `a bulk request whose parts are all admitted reports the furthest reservation`() {
        val server = Server()
        server.test({}, { clock }) {
            runBlocking {
                val response = server.smallBulk.test()
                assertEquals("200,200", response.body!!.text())
                assertEquals("caller", response.headers["X-RateLimit-Identity"]?.root)
                assertEquals(Instant.fromEpochSeconds(-40).toString(), response.headers["X-RateLimit-AvailableAfter"]?.root)
            }
        }
    }

    @Test
    fun `a bulk request gets no rate-limit headers when they are off`() {
        val server = Server()
        server.test({ server.limiterSettings set RateLimitSettings(includeHeaders = false) }, { clock }) {
            runBlocking {
                val response = server.bulk.test()
                assertEquals("200,200,200,429,429", response.body!!.text())
                assertNull(response.headers["X-RateLimit-Identity"])
            }
        }
    }

    @Test
    fun `a caller retrying faster than borrowTime stays blocked`() {
        val server = Server()
        server.test({}, { clock }) {
            runBlocking {
                repeat(3) { server.work.test() }
                repeat(20) {
                    assertEquals(HttpStatus.TooManyRequests, server.work.test().status)
                    time += 9.seconds
                }
            }
        }
    }

    @Test
    fun `a caller retrying slower than borrowTime gets in`() {
        val server = Server()
        server.test({}, { clock }) {
            runBlocking {
                repeat(3) { server.work.test() }
                var rejections = 0
                while (server.work.test().status == HttpStatus.TooManyRequests) {
                    rejections++
                    time += 11.seconds
                }
                // Claude: the caller starts 35 s over, and each retry pays it down by 11 s waited minus 10 s charged.
                assertEquals(35, rejections)
            }
        }
    }

    // Claude: without the refund the caller would keep only the 10 s borrowed and get a fourth request in.
    @Test
    fun `a cancelled request is still charged`() {
        val server = Server()
        server.test({}, { clock }) {
            runBlocking {
                val request = launch { server.abandoned.test() }
                server.started.await()
                request.cancelAndJoin()
                assertEquals(HttpStatus.OK, server.work.test().status)
                assertEquals(HttpStatus.OK, server.work.test().status)
                assertEquals(HttpStatus.TooManyRequests, server.work.test().status)
            }
        }
    }

    @Test
    fun `a free request on an idle caller succeeds`() {
        val server = object : ServerBuilder() {
            val limiterSettings = setting("limiter", RateLimitSettings())
            val cache = setting("cache", Cache.Settings("ram"))
            val free = path.path("free").get bind HttpHandler { HttpResponse.plainText("free") }

            init {
                registerBasicMediaTypeCoders()
                install(RateLimitInterceptor(limiterSettings, cache) { RequestLimits(key = "caller", overhead = 0.seconds) })
            }
        }
        server.test({}, { clock }) {
            runBlocking { assertEquals(HttpStatus.OK, server.free.test().status) }
        }
    }

    @Test
    fun `internal endpoint calls are not charged separately`() {
        val server = Server()
        server.test({}, { clock }) {
            runBlocking {
                val response = server.internalCaller.test()
                assertEquals("200,200,200,200,200", response.body!!.text())
                // The calling request paid for all 25 s, so the next request is over budget.
                assertNotNull(response.headers["X-RateLimit-Identity"])
                assertEquals(HttpStatus.TooManyRequests, server.work.test().status)
            }
        }
    }

    @Test
    fun `socket connects are charged`() {
        val server = Server()
        server.test({}, { clock }) {
            runBlocking {
                repeat(3) { server.socket.test() }
                val rejected = assertFailsWith<HttpStatusException> { server.socket.test() }
                assertEquals(HttpStatus.TooManyRequests, rejected.status)
            }
        }
    }

    @Test
    fun `messages on a connected socket are not charged`() {
        val server = Server()
        server.test({}, { clock }) {
            runBlocking {
                val socket = server.socket.test()
                repeat(10) { socket.send(WebSocketFrame.Text("hi")) }
                server.socket.test()
            }
        }
    }

    @Test
    fun `each virtual socket is charged on its own and the exempt multiplex socket is not`() {
        val server = Server()
        server.test({}, { clock }) {
            runBlocking {
                val json = engine.externalSerialization.json
                repeat(5) { server.multiplex.test() }
                val mux = server.multiplex.test()
                val received = ArrayList<MultiplexMessage>()
                mux.onMessageSent = { received.add(json.decodeFromString(MultiplexMessage.serializer(), it.text)) }

                for (channel in listOf("a", "b", "c", "d")) {
                    val start = MultiplexMessage(channel = channel, path = "/socket", start = true)
                    mux.send(WebSocketFrame.Text(json.encodeToString(MultiplexMessage.serializer(), start)))
                }

                assertEquals(listOf("a", "b", "c"), received.filter { it.start }.map { it.channel })
                val rejected = received.single { it.end }
                assertEquals("d", rejected.channel)
                assertNotNull(rejected.error)
            }
        }
    }

    @Test
    fun `a second limiter does not report the first one's reservation`() {
        val server = object : ServerBuilder() {
            val first = setting("first", RateLimitSettings(rateLimiterId = "first"))
            val second = setting("second", RateLimitSettings(rateLimiterId = "second"))
            val cache = setting("cache", Cache.Settings("ram"))
            val work = path.path("work").get bind HttpHandler { HttpResponse.plainText("worked") }

            init {
                registerBasicMediaTypeCoders()
                install(RateLimitInterceptor(first, cache) { RequestLimits(key = "caller") })
                install(RateLimitInterceptor(second, cache) { null })
            }
        }
        server.test({}, { clock }) {
            runBlocking {
                assertEquals(listOf("caller"), server.work.test().headers.getMany("X-RateLimit-Identity").map { it.root })
            }
        }
    }
}
