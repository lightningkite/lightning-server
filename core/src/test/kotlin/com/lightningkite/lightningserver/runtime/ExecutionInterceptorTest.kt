package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.definition.GeneralServerSettings
import com.lightningkite.lightningserver.definition.PreDeployTask
import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.HttpInterceptor
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.RawWebSocketPath
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours

/**
 * An [ExecutionInterceptor] is the only chain that claims to see *everything the server runs*, so
 * these tests check that claim directly: one interceptor, every kind of entry point, and an assertion
 * that each one arrived.
 */
class ExecutionInterceptorTest {

    private class Recorder : ExecutionInterceptor {
        val seen = ArrayList<Execution>()
        context(runtime: ServerRuntime)
        override suspend fun <T> intercept(
            cont: suspend context(ServerRuntime) () -> T,
        ): T {
            seen.add(runtime.execution)
            return cont()
        }
    }

    /** Records when it starts and finishes, so nesting order is visible in one flat list. */
    private class Marker(override val name: String, val log: MutableList<String>) : ExecutionInterceptor {
        context(runtime: ServerRuntime)
        override suspend fun <T> intercept(
            cont: suspend context(ServerRuntime) () -> T,
        ): T {
            log.add("$name in")
            try {
                return cont()
            } finally {
                log.add("$name out")
            }
        }
    }

    @OptIn(InternalLightningServerApi::class)
    @Test
    fun `every kind of execution passes through the chain`() {
        val recorder = Recorder()
        val server = object : ServerBuilder() {
            init {
                registerBasicMediaTypeCoders()
                install(recorder)
            }

            val endpoint = path.path("test").get bind HttpHandler { HttpResponse.plainText("hi") }
            val socket = path.path("socket") bind WebSocketHandler<PathSpec0, Unit>(willConnect = {})
            val task = path.path("task") bind Task(Unit.serializer()) {}
            val schedule = path.path("schedule") bind ScheduledTask(frequency = 1.hours) {}
            val startup = path.path("startup") bind StartupTask {}
            val preDeploy = path.path("preDeploy") bind PreDeployTask {}
        }
        server.test(settings = { generalSettings set GeneralServerSettings() }) {
            runBlocking {
                server.endpoint.test()
                server.task.executeInlineWithMetrics(server.task.location, Unit, serverRuntime.execution)
                server.schedule.executeWithMetrics(server.schedule.location)
                server.startup.executeWithMetrics(server.startup.location)
                server.preDeploy.executeWithMetrics(server.preDeploy.location)
                // The test runner drives sockets without the metrics helpers, so the socket seam is
                // exercised the way an engine does it.
                server.socket.willConnectWithMetrics(
                    location = server.socket.location,
                    initiator = Execution.WebSocket(
                        id = Execution.ID.generate(),
                        socketId = Execution.ID.generate(),
                        path = RawWebSocketPath("socket"),
                        phase = Execution.WebSocket.Phase.Connect,
                    ),
                    request = WebSocketConnectRequest(RawWebSocketPath("socket")),
                )
            }
        }

        assertEquals(
            listOf("Http", "Task", "Schedule", "Startup", "PreDeploy", "WebSocket"),
            recorder.seen.map { it::class.simpleName },
        )
    }

    @Test
    fun `the first installed interceptor is the outermost`() {
        val log = ArrayList<String>()
        val server = object : ServerBuilder() {
            init {
                registerBasicMediaTypeCoders()
                install(Marker("first", log))
                install(Marker("second", log))
            }

            val endpoint = path.path("test").get bind HttpHandler { HttpResponse.plainText("hi") }
        }
        server.test(settings = { generalSettings set GeneralServerSettings() }) {
            runBlocking { server.endpoint.test() }
        }

        assertEquals(listOf("first in", "second in", "second out", "first out"), log)
    }

    @Test
    fun `execution interceptors wrap the HTTP chain`() {
        val log = ArrayList<String>()
        val server = object : ServerBuilder() {
            init {
                registerBasicMediaTypeCoders()
                install(Marker("execution", log))
                install(HttpInterceptor { request, cont ->
                    log.add("http in")
                    try {
                        cont(request)
                    } finally {
                        log.add("http out")
                    }
                })
            }

            val endpoint = path.path("test").get bind HttpHandler {
                log.add("handler")
                HttpResponse.plainText("hi")
            }
        }
        server.test(settings = { generalSettings set GeneralServerSettings() }) {
            runBlocking { server.endpoint.test() }
        }

        assertEquals(
            listOf("execution in", "http in", "handler", "http out", "execution out"),
            log,
        )
    }
}
