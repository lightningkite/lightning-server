package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.EngineApi
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.MultiplexMessage
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.authReaders
import com.lightningkite.lightningserver.auth.register
import com.lightningkite.lightningserver.auth.testAuth
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.definition.PreDeployTask
import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.pathing.RawWebSocketPath
import com.lightningkite.lightningserver.runtime.EngineBase
import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.didConnectAsRoot
import com.lightningkite.lightningserver.runtime.disconnectAndCloseAsRoot
import com.lightningkite.lightningserver.runtime.execute
import com.lightningkite.lightningserver.runtime.executeInlineWithMetrics
import com.lightningkite.lightningserver.runtime.executeWithMetrics
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.handleRoot
import com.lightningkite.lightningserver.runtime.invoke
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.runtime.messageFromClientAsRoot
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.willConnectAsRoot
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.websockets.MultiplexWebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketClose
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketConnection
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionRequest
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.condition
import com.lightningkite.services.database.eq
import com.lightningkite.services.database.modification
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * Lazy origin recording across execution graphs.
 *
 * Each test drives a shape of execution graph through the real interceptor chain, has some executions
 * in it "touch" — call [OriginRecording.flush], as every audit layer does before
 * writing — and then checks which [OriginRecord]s exist and what they say.
 *
 * The properties under test:
 * - **Lazy:** an execution that touches nothing writes nothing, however deep the graph.
 * - **Complete:** a touch writes every origin it descends from that is not yet written, so nothing an
 *   audit record points at is missing.
 * - **Once:** a touch in an execution that already wrote its origins writes nothing more, and a
 *   repeated write elsewhere in the graph never produces a second row.
 *
 * "Writes nothing more" is checked by tampering: a row's `kind` is changed after it is written, and a
 * repeat upsert would put it back.
 */
@OptIn(InternalLightningServerApi::class, EngineApi::class)
class OriginRecordingTest {

    private val user = Uuid.parse("00000000-0000-4000-8000-0000000000e1")

    private fun probe(block: suspend context(ServerRuntime) (OriginProbeEngine) -> Unit) = runBlocking {
        val engine = OriginProbeEngine()
        engine.ready()
        engine.preDeploy()
        engine.direct { block(serverRuntime, engine) }
    }

    /** Every origin row, except the pre-deploy ones every probe writes while deploying. */
    context(server: ServerRuntime)
    private suspend fun rows(): List<OriginRecord<OriginRecord.StandardRequestInfo>> =
        OriginTestServer.audit.originsTable().find(Condition.Always).toList()
            .filter { it.kind != OriginRecord.ExecutionKind.PreDeploy }

    context(server: ServerRuntime)
    private suspend fun rowFor(id: Execution.ID): OriginRecord<OriginRecord.StandardRequestInfo>? =
        OriginTestServer.audit.originsTable().find(condition { it._id eq id.originId }).toList().singleOrNull()

    /** Marks [id]'s row so that any later rewrite of it is visible. */
    context(server: ServerRuntime)
    private suspend fun tamper(id: Execution.ID) {
        val changed = OriginTestServer.audit.originsTable().updateOne(
            condition { it._id eq id.originId },
            modification { it.kind assign TAMPERED },
        )
        assertNotNull(changed.new, "there was no row for $id to tamper with")
    }

    private fun assertUnique(rows: List<OriginRecord<OriginRecord.StandardRequestInfo>>) {
        assertEquals(rows.map { it._id }.distinct(), rows.map { it._id }, "an origin was written twice: $rows")
    }

    // ===================== a single root =====================

    @Test
    fun `an http request that touches nothing writes no origin`() = probe { engine ->
        val response = engine.handleRoot(engine.get("/quiet"), engine.newId())
        assertEquals(HttpStatus.OK, response.status)

        assertEquals(emptyList(), rows())
    }

    @Test
    fun `an http request that touches writes its own origin, with the request's details`() = probe { engine ->
        val id = engine.newId()
        engine.handleRoot(engine.get("/touch", authenticated = true), id)

        val row = assertNotNull(rowFor(id))
        assertEquals(listOf(row), rows())
        assertEquals(OriginRecord.ExecutionKind.Http, row.kind)
        assertEquals("GET /touch", row.location)
        assertNull(row.parent)
        assertEquals(id.toExternal(), row.root)

        val request = assertNotNull(row.request, "an http origin must carry its request")
        assertEquals("10.0.0.5", request.sourceIp)
        assertEquals("engine-req", request.engineRequestId)
        assertEquals("upstream-req", request.upstreamRequestId)
        assertEquals(user, request.subjectId)
    }

    @Test
    fun `an anonymous request's origin names no principal`() = probe { engine ->
        val id = engine.newId()
        engine.handleRoot(engine.get("/touch"), id)

        val request = assertNotNull(assertNotNull(rowFor(id)).request)
        assertNull(request.subjectId)
    }

    @Test
    fun `touching twice in one execution writes once`() = probe { engine ->
        val id = engine.newId()
        engine.handleRoot(engine.get("/touch-tamper-touch"), id)

        assertEquals(TAMPERED, assertNotNull(rowFor(id)).kind, "the second touch rewrote the origin")
        assertEquals(1, rows().size)
    }

    @Test
    fun `a direct execution records a direct origin`() = probe { engine ->
        val id = engine.newId()
        engine.direct(id) { OriginTestServer.audit.origins.flush() }

        val row = assertNotNull(rowFor(id))
        assertEquals(OriginRecord.ExecutionKind.Direct, row.kind)
        assertNull(row.request)
        assertEquals(id.toExternal(), row.root)
    }

    @Test
    fun `a schedule tick records a schedule origin`() = probe { engine ->
        OriginTestServer.touchSchedule.executeWithMetrics()

        val row = rows().single()
        assertEquals(OriginRecord.ExecutionKind.Schedule, row.kind)
        assertTrue("touch-schedule" in row.location, row.location)
        assertNull(row.request)
        assertNull(row.parent)
    }

    @Test
    fun `a startup task records a startup origin`() = probe { engine ->
        OriginTestServer.touchStartup.executeWithMetrics()

        val row = rows().single()
        assertEquals(OriginRecord.ExecutionKind.Startup, row.kind)
        assertTrue("touch-startup" in row.location, row.location)
    }

    @Test
    fun `a pre-deploy task records a pre-deploy origin`() = probe { engine ->
        val all = OriginTestServer.audit.originsTable().find(Condition.Always).toList()
        val row = all.single { it.kind == OriginRecord.ExecutionKind.PreDeploy }
        assertTrue("touch-predeploy" in row.location, row.location)
    }

    @Test
    fun `a schedule tick that touches nothing writes no origin`() = probe { engine ->
        OriginTestServer.quietSchedule.executeWithMetrics()

        assertEquals(emptyList(), rows())
    }

    // ===================== nested http (bulk-style sub-requests) =====================

    @Test
    fun `a sub-request that touches writes its own origin and its untouched parent's`() = probe { engine ->
        val parent = engine.newId()
        engine.handleRoot(engine.get("/nest/child-touches"), parent)

        val child = engine.seen.single { it.endpointIs("/touch") }
        val parentRow = assertNotNull(rowFor(parent), "the parent was never written, so the child's row dangles")
        val childRow = assertNotNull(rowFor(child.id))
        assertEquals(2, rows().size)

        assertEquals("GET /nest/child-touches", parentRow.location)
        assertEquals(parent.toExternal(), childRow.parent)
        assertEquals(parent.toExternal(), childRow.root)
        assertEquals("10.0.0.5", parentRow.request?.sourceIp)
    }

    @Test
    fun `a sub-request that touches nothing writes nothing for itself or its parent`() = probe { engine ->
        engine.handleRoot(engine.get("/nest/child-quiet"), engine.newId())

        assertEquals(emptyList(), rows())
    }

    @Test
    fun `a parent that touched before its sub-request is not rewritten by it`() = probe { engine ->
        val parent = engine.newId()
        engine.handleRoot(engine.get("/nest/parent-touches-tampers-then-child-touches"), parent)

        val child = engine.seen.single { it.endpointIs("/touch") }
        assertEquals(TAMPERED, assertNotNull(rowFor(parent)).kind, "the sub-request rewrote its parent's origin")
        assertNotNull(rowFor(child.id))
        assertEquals(2, rows().size)
    }

    /**
     * The child's clear only reaches the child's own context, so the parent still holds itself as
     * pending and writes again. That repeat must land on the same row.
     */
    @Test
    fun `a parent that touches after its sub-request did ends with one row`() = probe { engine ->
        val parent = engine.newId()
        engine.handleRoot(engine.get("/nest/child-touches-then-parent-touches"), parent)

        val all = rows()
        assertUnique(all)
        assertEquals(2, all.size)
        assertNotNull(rowFor(parent))
    }

    @Test
    fun `two sibling sub-requests that both touch write their shared parent once`() = probe { engine ->
        val parent = engine.newId()
        engine.handleRoot(engine.get("/nest/two-children-touch"), parent)

        val all = rows()
        assertUnique(all)
        assertEquals(3, all.size, "expected the parent and both children: $all")
        val children = engine.seen.filter { it.endpointIs("/touch") }
        assertEquals(2, children.size)
        for (child in children) assertEquals(parent.toExternal(), assertNotNull(rowFor(child.id)).parent)
    }

    @Test
    fun `a grandchild that touches writes the whole chain`() = probe { engine ->
        val root = engine.newId()
        engine.handleRoot(engine.get("/nest/grandchild-touches"), root)

        val middle = engine.seen.single { it.endpointIs("/nest/child-touches") }
        val leaf = engine.seen.single { it.endpointIs("/touch") }
        assertEquals(3, rows().size)
        assertEquals(root.toExternal(), assertNotNull(rowFor(middle.id)).parent)
        assertEquals(middle.id.toExternal(), assertNotNull(rowFor(leaf.id)).parent)
        assertEquals(root.toExternal(), assertNotNull(rowFor(leaf.id)).root)
        assertNotNull(rowFor(root))
    }

    // ===================== tasks =====================

    /** The task carries its launcher's pending origin across the queue and writes it; it has no row of its own. */
    @Test
    fun `a task launched from an untouched request writes the request's origin`() = probe { engine ->
        val request = engine.newId()
        engine.handleRoot(engine.get("/launch/touch-task"), request)
        assertEquals(emptyList(), rows(), "launching a task is not itself a touch")

        engine.drainTasks()

        val row = rows().single()
        assertEquals(request.originId, row._id, "the task should write the request it descends from")
        assertEquals(OriginRecord.ExecutionKind.Http, row.kind)
        assertEquals("10.0.0.5", row.request?.sourceIp, "the request details must survive the queue")
    }

    @Test
    fun `a task that touches nothing writes nothing for its launcher`() = probe { engine ->
        engine.handleRoot(engine.get("/launch/quiet-task"), engine.newId())
        engine.drainTasks()

        assertEquals(emptyList(), rows())
    }

    @Test
    fun `a task launched after its request touched does not rewrite the request`() = probe { engine ->
        val request = engine.newId()
        engine.handleRoot(engine.get("/touch-then-launch"), request)
        tamper(request)

        engine.drainTasks()

        assertEquals(TAMPERED, assertNotNull(rowFor(request)).kind, "the task rewrote an origin already recorded")
        assertEquals(1, rows().size)
    }

    /**
     * The request touches *after* launching, so the queued payload still says pending. The task's
     * repeat write must land on the same row.
     */
    @Test
    fun `a task launched before its request touched ends with one row`() = probe { engine ->
        val request = engine.newId()
        engine.handleRoot(engine.get("/launch-then-touch"), request)
        engine.drainTasks()

        val all = rows()
        assertUnique(all)
        assertEquals(listOf(request.originId), all.map { it._id })
    }

    @Test
    fun `two tasks from one untouched request write it once`() = probe { engine ->
        val request = engine.newId()
        engine.handleRoot(engine.get("/launch/two-touch-tasks"), request)
        engine.drainTasks()

        assertEquals(listOf(request.originId), rows().map { it._id })
    }

    @Test
    fun `a task launched by a task still writes the original request`() = probe { engine ->
        val request = engine.newId()
        engine.handleRoot(engine.get("/launch/chain-task"), request)
        engine.drainTasks()

        assertEquals(listOf(request.originId), rows().map { it._id })
    }

    @Test
    fun `a task launched by a sub-request writes the sub-request and its parent`() = probe { engine ->
        val parent = engine.newId()
        engine.handleRoot(engine.get("/nest/child-launches"), parent)
        engine.drainTasks()

        val child = engine.seen.single { it.endpointIs("/launch/touch-task") }
        assertEquals(setOf(parent.originId, child.id.originId), rows().map { it._id }.toSet())
    }

    @Test
    fun `a task launched from a schedule tick writes the tick`() = probe { engine ->
        OriginTestServer.launchingSchedule.executeWithMetrics()
        assertEquals(emptyList(), rows())

        engine.drainTasks()

        assertEquals(OriginRecord.ExecutionKind.Schedule, rows().single().kind)
    }

    // ===================== websockets =====================

    @Test
    fun `connecting a socket writes no origin`() = probe { engine ->
        engine.openSocket(OriginTestServer.socket, authenticated = true)

        assertEquals(emptyList(), rows())
    }

    @Test
    fun `a socket that only sends quiet messages writes no origin`() = probe { engine ->
        val socket = engine.openSocket(OriginTestServer.socket)
        socket.send("quiet")
        socket.send("quiet")

        assertEquals(emptyList(), rows())
    }

    /** The details come from the connect request, which a message phase has no other way to see. */
    @Test
    fun `the first touching message writes the socket's origin, with the details captured at connect`() =
        probe { engine ->
            val socket = engine.openSocket(OriginTestServer.socket, authenticated = true)
            socket.send("touch")

            val row = assertNotNull(rowFor(socket.request.socketId))
            assertEquals(listOf(row), rows(), "a message phase should not have an origin row of its own")
            assertEquals(OriginRecord.ExecutionKind.WebSocket, row.kind)
            assertEquals("/socket", row.location)
            assertNull(row.parent)
            assertEquals(socket.request.socketId.toExternal(), row.root)
            assertEquals("10.0.0.9", row.request?.sourceIp)
            assertEquals(OriginProbeEngine.USER, row.request?.subjectId)
        }

    @Test
    fun `a touch during connect writes the socket's origin`() = probe { engine ->
        val socket = engine.openSocket(OriginTestServer.touchOnConnectSocket)

        assertEquals(listOf(socket.request.socketId.originId), rows().map { it._id })
    }

    /** Each phase starts fresh, so each writes again; every one of those writes must land on the same row. */
    @Test
    fun `several touching phases leave one row`() = probe { engine ->
        val socket = engine.openSocket(OriginTestServer.socket)
        socket.send("touch")
        socket.send("touch")
        socket.disconnect()

        assertEquals(listOf(socket.request.socketId.originId), rows().map { it._id })
    }

    @Test
    fun `a socket's disconnect can touch`() = probe { engine ->
        val socket = engine.openSocket(OriginTestServer.socket)
        socket.disconnect()

        assertEquals(listOf(socket.request.socketId.originId), rows().map { it._id })
    }

    @Test
    fun `a task launched from an untouched socket writes the socket's origin`() = probe { engine ->
        val socket = engine.openSocket(OriginTestServer.socket, authenticated = true)
        socket.send("task")
        assertEquals(emptyList(), rows(), "launching a task is not itself a touch")

        engine.drainTasks()

        val row = rows().single()
        assertEquals(socket.request.socketId.originId, row._id)
        assertEquals(OriginRecord.ExecutionKind.WebSocket, row.kind)
        assertEquals(OriginProbeEngine.USER, row.request?.subjectId)
    }

    @Test
    fun `starting a virtual sub-socket writes no origin`() = probe { engine ->
        val physical = engine.openSocket(OriginTestServer.multiplex)
        engine.startChannel(physical, "c1")

        assertEquals(emptyList(), rows())
    }

    /**
     * The sub-socket's message phase is dispatched from inside the physical socket's, so it carries
     * the physical socket's origin as well as its own. The two share one request cache, so this also
     * checks that neither socket's captured origin overwrote the other's.
     */
    @Test
    fun `a virtual sub-socket's touch writes its own origin and its carrier's`() = probe { engine ->
        val physical = engine.openSocket(OriginTestServer.multiplex)
        engine.startChannel(physical, "c1")
        engine.sendOnChannel(physical, "c1", "touch")

        val sub = engine.seen.filterIsInstance<Execution.WebSocket>()
            .single { it.phase == Execution.WebSocket.Phase.Connect && it.socketId != physical.request.socketId }
        val all = rows()
        assertUnique(all)
        assertEquals(setOf(physical.request.socketId.originId, sub.socketId.originId), all.map { it._id }.toSet())

        assertEquals("/multiplex", assertNotNull(rowFor(physical.request.socketId)).location)
        val subRow = assertNotNull(rowFor(sub.socketId))
        assertEquals("/socket", subRow.location)
        assertEquals(sub.parent?.toExternal(), subRow.parent, "a virtual socket is parented to the phase that started it")
        assertNotNull(subRow.parent)
    }

    @Test
    fun `two virtual sub-sockets on one carrier each write their own origin`() = probe { engine ->
        val physical = engine.openSocket(OriginTestServer.multiplex)
        engine.startChannel(physical, "c1")
        engine.startChannel(physical, "c2")
        engine.sendOnChannel(physical, "c1", "touch")
        engine.sendOnChannel(physical, "c2", "touch")

        val subs = engine.seen.filterIsInstance<Execution.WebSocket>()
            .filter { it.phase == Execution.WebSocket.Phase.Connect && it.socketId != physical.request.socketId }
            .map { it.socketId.originId }
        assertEquals(2, subs.size)
        val all = rows()
        assertUnique(all)
        assertEquals((subs + physical.request.socketId.originId).toSet(), all.map { it._id }.toSet())
    }

    // ===================== failure =====================

    /** Cannot happen through the framework; guards against an execution kind the interceptor misses. */
    @Test
    fun `touching with no captured origin fails rather than writing a dangling reference`() = probe { engine ->
        val failure = runCatching {
            engine.handleRoot(engine.get("/launch/touch-task"), engine.newId())
            engine.drainTasksWithoutContext()
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException, "expected the missing origin to be refused, got $failure")
        assertEquals(emptyList(), rows())
    }
}

/** A kind no real origin is written with, used to spot a rewrite. */
private val TAMPERED = OriginRecord.ExecutionKind.Startup

private fun Execution.endpointIs(route: String): Boolean =
    this is Execution.Http && endpoint.toString().substringBefore('?').endsWith(route)

// ===================== the server under test =====================

@Serializable
private data class OriginUser(override val _id: Uuid) : HasId<Uuid> {
    companion object : PrincipalType<OriginUser, Uuid> {
        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
        override val subjectSerializer: KSerializer<OriginUser> = serializer()

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): OriginUser = OriginUser(id)
    }
}

private object OriginTokenReader : Authentication.Reader<OriginUser> {
    override val priority: Double = 1.0

    context(server: ServerRuntime)
    override suspend fun read(request: Request<*>): Authentication<OriginUser>? {
        val raw = request.headers[HttpHeader.Authorization]?.root ?: return null
        return OriginUser.testAuth(OriginUser(Uuid.parse(raw)))
    }
}

@OptIn(InternalLightningServerApi::class)
private object OriginTestServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())

    val audit = path.path("audit") include AuditCore(database)

    init {
        registerBasicMediaTypeCoders()
        register(OriginUser)
        authReaders.register(OriginTokenReader)
    }

    context(runtime: ServerRuntime)
    private suspend fun touch() = audit.origins.flush()

    context(runtime: ServerRuntime)
    private fun note() {
        (runtime.processEngine as OriginProbeEngine).seen += runtime.execution
    }

    context(runtime: ServerRuntime)
    private suspend fun sub(path: String): HttpResponse {
        val response = runtime.handle(subRequest(path))
        check(response.status == HttpStatus.OK) { "Sub-request to $path failed: ${response.status}" }
        return response
    }

    private fun ok() = HttpResponse.plainText("ok")

    val quiet = path.path("quiet").get bind HttpHandler { note(); ok() }

    val touchEndpoint = path.path("touch").get bind HttpHandler { note(); touch(); ok() }

    val touchTamperTouch = path.path("touch-tamper-touch").get bind HttpHandler {
        note()
        touch()
        audit.originsTable().updateOne(
            condition { it._id eq serverRuntime.execution.id.originId },
            modification { it.kind assign TAMPERED },
        )
        touch()
        ok()
    }

    val childTouches = path.path("nest").path("child-touches").get bind HttpHandler { note(); sub("/touch"); ok() }
    val childQuiet = path.path("nest").path("child-quiet").get bind HttpHandler { note(); sub("/quiet"); ok() }
    val parentTouchesThenChild =
        path.path("nest").path("parent-touches-tampers-then-child-touches").get bind HttpHandler {
            note()
            touch()
            audit.originsTable().updateOne(
                condition { it._id eq serverRuntime.execution.id.originId },
                modification { it.kind assign TAMPERED },
            )
            sub("/touch")
            ok()
        }
    val childThenParent = path.path("nest").path("child-touches-then-parent-touches").get bind HttpHandler {
        note(); sub("/touch"); touch(); ok()
    }
    val twoChildren = path.path("nest").path("two-children-touch").get bind HttpHandler {
        note(); sub("/touch"); sub("/touch"); ok()
    }
    val grandchild = path.path("nest").path("grandchild-touches").get bind HttpHandler {
        note(); sub("/nest/child-touches"); ok()
    }
    val childLaunches = path.path("nest").path("child-launches").get bind HttpHandler {
        note(); sub("/launch/touch-task"); ok()
    }

    val touchTask: Task<Unit> = path.path("touch-task") bind Task(Unit.serializer()) { touch() }
    val quietTask: Task<Unit> = path.path("quiet-task") bind Task(Unit.serializer()) { }
    val chainTask: Task<Unit> = path.path("chain-task") bind Task(Unit.serializer()) { touchTask(Unit) }

    val launchTouch = path.path("launch").path("touch-task").get bind HttpHandler { note(); touchTask(Unit); ok() }
    val launchQuiet = path.path("launch").path("quiet-task").get bind HttpHandler { note(); quietTask(Unit); ok() }
    val launchTwo = path.path("launch").path("two-touch-tasks").get bind HttpHandler {
        note(); touchTask(Unit); touchTask(Unit); ok()
    }
    val launchChain = path.path("launch").path("chain-task").get bind HttpHandler { note(); chainTask(Unit); ok() }
    val touchThenLaunch = path.path("touch-then-launch").get bind HttpHandler { note(); touch(); touchTask(Unit); ok() }
    val launchThenTouch = path.path("launch-then-touch").get bind HttpHandler { note(); touchTask(Unit); touch(); ok() }

    val touchSchedule = path.path("touch-schedule") bind ScheduledTask(frequency = 1.hours) { touch() }
    val quietSchedule = path.path("quiet-schedule") bind ScheduledTask(frequency = 1.hours) { }
    val launchingSchedule = path.path("launching-schedule") bind ScheduledTask(frequency = 1.hours) { touchTask(Unit) }
    val touchStartup = path.path("touch-startup") bind StartupTask { touch() }
    val touchPreDeploy = path.path("touch-predeploy") bind PreDeployTask { touch() }

    val socket = path.path("socket") bind WebSocketHandler<PathSpec0, Unit>(
        willConnect = { note() },
        messageFromClient = { frame ->
            note()
            when ((frame as WebSocketFrame.Text).content) {
                "touch" -> touch()
                "task" -> touchTask(Unit)
                "quiet" -> {}
                else -> throw IllegalArgumentException("Unknown socket command: $frame")
            }
        },
        disconnect = { note(); touch() },
    )

    val multiplex = path.path("multiplex") bind MultiplexWebSocketHandler()

    val touchOnConnectSocket = path.path("touch-on-connect") bind WebSocketHandler<PathSpec0, Unit>(
        willConnect = { note(); touch() },
    )

    private fun subRequest(path: String) = HttpRequest<PathSpec>(
        path = RawHttpEndpoint(asString = path, method = HttpMethod.GET),
        queryParameters = QueryParameters.EMPTY,
        headers = HttpHeaders { },
        domain = "example.com",
        protocol = "https",
        sourceIp = "10.0.0.5",
    )
}

// ===================== the engine =====================

/**
 * Drives requests, sockets and tasks through the real interceptor chain.
 *
 * The task queue keeps the launching execution only as JSON, as a serverless queue would, so a task
 * sees exactly the origin state its launcher had at the moment it launched — not anything the
 * launcher did afterwards.
 */
@OptIn(InternalLightningServerApi::class, EngineApi::class)
private class OriginProbeEngine : EngineBase(OriginTestServer.build()) {
    override val serverId: String = "origin-probe"
    override val serverVersion: String = "test"

    /** Every execution a handler on [OriginTestServer] ran in, in order. */
    val seen: MutableList<Execution> = mutableListOf()

    fun newId(): Execution.ID = Execution.ID.generate()

    suspend fun <T> direct(id: Execution.ID = newId(), action: suspend context(ServerRuntime) () -> T): T =
        execute("probe", Execution.Direct(id)) { action(this) }

    override suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(
        event: WebSocketSubscriptionMessage<PATH, T>,
    ): Unit = Unit

    fun ready() {
        with(settings) {
            OriginTestServer.database set Database.Settings()
            OriginTestServer.cache set Cache.Settings()
        }
        settings.readyUsingDefaults()
    }

    suspend fun preDeploy(): Unit = runPreDeployTasks()

    fun get(path: String, authenticated: Boolean = false) = HttpRequest<PathSpec>(
        path = RawHttpEndpoint(asString = path, method = HttpMethod.GET),
        queryParameters = QueryParameters.EMPTY,
        headers = HttpHeaders { if (authenticated) add(HttpHeader.Authorization, USER.toString()) },
        domain = "example.com",
        protocol = "https",
        sourceIp = "10.0.0.5",
        upstreamRequestId = "upstream-req",
        engineRequestId = "engine-req",
    )

    // ----- tasks -----

    @Serializable
    private data class Queued(val location: String, val from: String, val input: String)

    private val queue = ArrayDeque<Queued>()

    override suspend fun <T> dispatchTask(task: Task<T>, input: T, from: Execution) {
        queue.addLast(
            Queued(
                location = task.location.toString(),
                from = internalSerialization.json.encodeToString(Execution.serializer(), from),
                input = internalSerialization.json.encodeToString(task.serializer, input),
            )
        )
    }

    suspend fun drainTasks() = drain { it }

    /** Drains as if the queue had lost every launcher's context, which no real queue does. */
    suspend fun drainTasksWithoutContext() = drain {
        when (it) {
            is Execution.Http -> it.copy(context = com.lightningkite.lightningserver.data.SerializableCache())
            else -> error("Unexpected launcher $it")
        }
    }

    private suspend fun drain(transform: (Execution) -> Execution) {
        while (queue.isNotEmpty()) {
            val queued = queue.removeFirst()
            @Suppress("UNCHECKED_CAST")
            val task = server.tasks.getValue(PathSpec0.fromString(queued.location)) as Task<Any?>
            task.executeInlineWithMetrics(
                internalSerialization.json.decodeFromString(task.serializer, queued.input),
                transform(internalSerialization.json.decodeFromString(Execution.serializer(), queued.from)),
            )
        }
    }

    // ----- sockets -----

    inner class ProbeSocket<STORAGE>(
        private val handler: WebSocketHandler<PathSpec0, STORAGE>,
        val request: WebSocketConnectRequest<PathSpec0>,
        private var state: STORAGE,
    ) {
        val sent: MutableList<WebSocketFrame> = mutableListOf()

        val connection: WebSocketConnection<PathSpec0, STORAGE> = object : WebSocketConnection<PathSpec0, STORAGE> {
            override val request: WebSocketConnectRequest<PathSpec0> get() = this@ProbeSocket.request
            override val currentState: STORAGE get() = state

            context(server: ServerRuntime)
            override suspend fun repullState(): STORAGE = state

            context(server: ServerRuntime)
            override suspend fun queueStateUpdate(modification: (STORAGE) -> STORAGE) {
                state = modification(state)
            }

            context(server: ServerRuntime)
            override suspend fun updateStateImmediately(modification: (STORAGE) -> STORAGE): STORAGE {
                state = modification(state)
                return state
            }

            context(server: ServerRuntime)
            override suspend fun subscribe(topic: WebSocketSubscriptionRequest<*, *>): Unit = Unit

            context(server: ServerRuntime)
            override suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>): Unit = Unit

            context(server: ServerRuntime)
            override suspend fun send(frame: WebSocketFrame) {
                sent += frame
            }

            context(server: ServerRuntime)
            override suspend fun close(reason: WebSocketClose): Unit = Unit
        }

        suspend fun send(text: String) {
            handler.messageFromClientAsRoot(connection, WebSocketFrame.Text(text))
        }

        suspend fun disconnect() {
            handler.disconnectAndCloseAsRoot(connection, WebSocketClose(WebSocketClose.Code.NORMAL, null))
        }
    }

    suspend fun <STORAGE> openSocket(
        handler: WebSocketHandler<PathSpec0, STORAGE>,
        authenticated: Boolean = false,
    ): ProbeSocket<STORAGE> {
        val intercepted = server.interceptIncomingSocket(handler)
        val request = WebSocketConnectRequest(
            path = RawWebSocketPath(handler.location),
            socketId = newId(),
            headers = HttpHeaders { if (authenticated) add(HttpHeader.Authorization, USER.toString()) },
            domain = "example.com",
            protocol = "wss",
            sourceIp = "10.0.0.9",
        )
        val storage = intercepted.willConnectAsRoot(request)
        return ProbeSocket(intercepted, request, storage).also {
            intercepted.didConnectAsRoot(it.connection)
        }
    }

    suspend fun startChannel(physical: ProbeSocket<*>, channel: String) {
        physical.send(
            externalSerialization.json.encodeToString(MultiplexMessage(channel = channel, path = "/socket", start = true))
        )
        failOnMultiplexError(physical)
    }

    suspend fun sendOnChannel(physical: ProbeSocket<*>, channel: String, data: String) {
        physical.send(externalSerialization.json.encodeToString(MultiplexMessage(channel = channel, data = data)))
        failOnMultiplexError(physical)
    }

    private fun failOnMultiplexError(physical: ProbeSocket<*>) {
        physical.sent.mapNotNull { (it as? WebSocketFrame.Text)?.content }
            .map { externalSerialization.json.decodeFromString<MultiplexMessage>(it) }
            .firstOrNull { it.error != null }
            ?.let { throw IllegalStateException("The multiplexed socket failed: ${it.error}") }
    }

    companion object {
        val USER: Uuid = Uuid.parse("00000000-0000-4000-8000-0000000000e1")
    }
}
