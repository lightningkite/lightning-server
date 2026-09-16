package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.MultiplexMessage
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.authReaders
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.auth.register
import com.lightningkite.lightningserver.auth.testAuth
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.generateRequestId
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.RawWebSocketPath
import com.lightningkite.lightningserver.pathing.path
import com.lightningkite.lightningserver.runtime.EngineBase
import com.lightningkite.lightningserver.runtime.ExecutionCause
import com.lightningkite.lightningserver.runtime.Initiator
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.executeWithMetrics
import com.lightningkite.lightningserver.runtime.forExecution
import com.lightningkite.lightningserver.runtime.invoke
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.runtime.phase
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.lightningserver.websockets.MultiplexWebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketClose
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
import kotlin.uuid.Uuid

/**
 * Can a change made under a WebSocket be traced back to a person?
 *
 * A [MutationRecord] names a `rootExecutionId`; turning that into a person means looking it up in
 * [RequestRecord] and reading `principal`. For HTTP that join is trivially sound — the row is keyed by
 * the request's own execution id. For a socket it is not obvious: [RequestRecordInterceptor] keys the
 * row by the *socket* id rather than by the phase execution that wrote the change, and a virtual
 * socket multiplexed inside a physical one gets a fresh socket id while inheriting the physical
 * connection's root.
 *
 * These tests answer that by driving real sockets through the real interceptor chain and then
 * performing exactly the join an auditor would: `requests().find { it._id == mutation.attributedTo }`.
 *
 * `attributedTo` rather than `rootExecutionId` throughout, and the difference is the point. The root
 * is a *causal* key — it answers "what set this off" — and the head of a causal chain is not
 * necessarily the execution that carried the credentials. A multiplexed socket is exactly where the
 * two come apart, so the tests below assert both, and assert that they disagree where they should.
 */
@OptIn(InternalLightningServerApi::class)
class WebSocketMutationAttributionTest {

    private val user = Uuid.parse("00000000-0000-4000-8000-0000000000c1")

    private fun probe(block: suspend context(ServerRuntime) (ProbeEngine) -> Unit) = runBlocking {
        val engine = ProbeEngine()
        engine.ready()
        engine.preDeploy()
        block(engine, engine)
    }

    context(server: ServerRuntime)
    private suspend fun requests() = TestServer.audit.requests().find(Condition.Always).toList()

    context(server: ServerRuntime)
    private suspend fun mutations() = TestServer.mutationLog.mutations().find(Condition.Always).toList()

    private fun authHeaders() = HttpHeaders { add(HttpHeader.Authorization, user.toString()) }

    /** The same credential as [authHeaders], carried the way a browser has to carry it on a socket. */
    private fun tokenQuery() = mapOf(HttpHeader.Authorization to listOf(user.toString()))

    /** The join an auditor performs, as one call, so every test below asks the same question. */
    private fun List<RequestRecord>.rowFor(id: Uuid?): RequestRecord? = firstOrNull { it._id == id }

    // ===================== 1. a plain socket, mutating directly =====================

    @Test
    fun `a mutation made by a socket message joins back to the person through both ids`() = probe { engine ->
        val socket = engine.openSocket(TestServer.socket, authHeaders())
        val id = Uuid.random()
        socket.send("direct:$id")

        val mutation = mutations().single()
        assertEquals(id.toString(), mutation.recordId)

        val requests = requests()
        val byRequestId = requests.rowFor(mutation.requestId)
        assertNotNull(byRequestId, "requestId ${mutation.requestId} names no request row; rows are ${requests.map { it._id }}")
        val byRoot = requests.rowFor(mutation.rootExecutionId)
        assertNotNull(byRoot, "rootExecutionId ${mutation.rootExecutionId} names no request row; rows are ${requests.map { it._id }}")

        assertEquals(socket.initiator.socketId, mutation.requestId, "the row is keyed by the socket, not the phase")

        val byAttribution = requests.rowFor(mutation.attributedTo)
        assertNotNull(byAttribution, "attributedTo ${mutation.attributedTo} names no request row")
        assertTrue(
            byAttribution.principal?.contains(user.toString()) == true,
            "the request the change attributes to does not name the authenticated user: ${byAttribution.principal}",
        )
        // For a plain socket the three ids coincide, which is why one column looked like enough.
        assertEquals(byAttribution._id, byRoot._id)
    }

    // ===================== 2. a plain socket, mutating from a task =====================

    @Test
    fun `a mutation made by a task launched from a socket message joins back to the person`() = probe { engine ->
        val socket = engine.openSocket(TestServer.socket, authHeaders())
        val id = Uuid.random()
        socket.send("task:$id")
        engine.drainTasks()

        val mutation = mutations().single()
        assertEquals("task", mutation.initiatorKind, "the task ran inline rather than as its own execution")
        assertNull(mutation.requestId, "a task has no request row of its own to point at")

        val requests = requests()
        val byAttribution = requests.rowFor(mutation.attributedTo)
        assertNotNull(byAttribution, "attributedTo ${mutation.attributedTo} names no request row; rows are ${requests.map { it._id }}")
        assertEquals(
            socket.initiator.socketId,
            byAttribution._id,
            "the task should attribute to the socket it descends from",
        )
        assertTrue(
            byAttribution.principal?.contains(user.toString()) == true,
            "the request the change attributes to does not name the authenticated user: ${byAttribution.principal}",
        )
        // The anchor survived the queue: it is carried in the serialized ExecutionCause, not derived.
        assertEquals(byAttribution._id, requests.rowFor(mutation.rootExecutionId)?._id)
    }

    // ===================== 3. a virtual socket multiplexed inside a physical one =====================

    @Test
    fun `a mutation made by a virtual sub-socket message joins back to the person`() = probe { engine ->
        val physical = engine.openSocket(TestServer.multiplex, authHeaders())
        val id = Uuid.random()
        engine.startChannel(physical, "c1")
        engine.sendOnChannel(physical, "c1", "direct:$id")

        val mutation = mutations().single()
        assertEquals(id.toString(), mutation.recordId)

        val requests = requests()
        val byRequestId = requests.rowFor(mutation.requestId)
        assertNotNull(byRequestId, "requestId ${mutation.requestId} names no request row; rows are ${requests.map { it._id to it.endpoint }}")
        val byRoot = requests.rowFor(mutation.rootExecutionId)
        assertNotNull(byRoot, "rootExecutionId ${mutation.rootExecutionId} names no request row; rows are ${requests.map { it._id to it.endpoint }}")

        assertEquals(
            physical.initiator.socketId,
            byRoot._id,
            "the root of a virtual socket's work should be the physical connection carrying it",
        )

        val byAttribution = requests.rowFor(mutation.attributedTo)
        assertNotNull(byAttribution, "attributedTo ${mutation.attributedTo} names no request row")
        assertEquals("/socket", byAttribution.endpoint, "a sub-socket attributes to its own row, not the carrier's")
        assertTrue(
            byAttribution.principal?.contains(user.toString()) == true,
            "the request the change attributes to does not name the authenticated user: ${byAttribution.principal}",
        )
    }

    @Test
    fun `a mutation made by a task launched from a virtual sub-socket message joins back to the person`() =
        probe { engine ->
            val physical = engine.openSocket(TestServer.multiplex, authHeaders())
            val id = Uuid.random()
            engine.startChannel(physical, "c1")
            engine.sendOnChannel(physical, "c1", "task:$id")
            engine.drainTasks()

            val mutation = mutations().single()
            assertEquals("task", mutation.initiatorKind)
            assertNull(mutation.requestId)

            val requests = requests()
            val byAttribution = requests.rowFor(mutation.attributedTo)
            assertNotNull(
                byAttribution,
                "attributedTo ${mutation.attributedTo} names no request row; rows are ${requests.map { it._id to it.endpoint }}",
            )
            assertEquals(
                "/socket",
                byAttribution.endpoint,
                "the anchor a task inherits is the sub-socket's, not the carrier the root names",
            )
            assertTrue(
                byAttribution.principal?.contains(user.toString()) == true,
                "the request the change attributes to does not name the authenticated user: ${byAttribution.principal}",
            )
        }

    // ===== 4. a virtual sub-socket authenticated where the carrier is not =====

    /**
     * `WebSocketConnectRequest.subConnection` hands the sub-connection the carrier's *same*
     * [com.lightningkite.lightningserver.data.SerializableCache] instance, so an unauthenticated
     * carrier resolving `Authentication.CacheKey` first could plausibly memoize a null that the
     * sub-socket then inherits. It does not: `SerializableCache.get(CalculatingKey, input)` is
     * `retrieve(key)?.value ?: calculate(input)`, so a cached null falls through to a fresh
     * calculation against the sub-socket's own request. Pinned because the failure mode it rules out
     * would be silent and would look exactly like "the user did not authenticate".
     */
    @Test
    fun `a virtual sub-socket authenticates on its own credentials even when the carrier did not`() =
        probe { engine ->
            val physical = engine.openSocket(TestServer.multiplex, HttpHeaders.EMPTY)
            engine.startChannel(physical, "c1", tokenQuery())
            engine.sendOnChannel(physical, "c1", "direct:${Uuid.random()}")

            val requests = requests()
            val carrier = requests.rowFor(physical.initiator.socketId)
            assertNotNull(carrier)
            assertNull(carrier.principal, "the carrier was supposed to be anonymous")

            val sub = requests.single { it.endpoint == "/socket" }
            assertTrue(
                sub.principal?.contains(user.toString()) == true,
                "the sub-socket did not authenticate on its own query parameters: ${sub.principal}",
            )
        }

    /**
     * The two columns pulling apart, on one row.
     *
     * `attributedTo` names the sub-socket, which authenticated; `rootExecutionId` names the carrier,
     * which did not. Both are correct answers to their own question — "who did it" and "what set
     * this off" — and pinned together here so that neither can later be collapsed into the other on
     * the grounds that they usually agree.
     */
    @Test
    fun `a direct mutation from an authenticated sub-socket attributes to the person while its root stays anonymous`() =
        probe { engine ->
            val physical = engine.openSocket(TestServer.multiplex, HttpHeaders.EMPTY)
            engine.startChannel(physical, "c1", tokenQuery())
            engine.sendOnChannel(physical, "c1", "direct:${Uuid.random()}")

            val requests = requests()
            val mutation = mutations().single()

            val byAttribution = requests.rowFor(mutation.attributedTo)
            assertNotNull(byAttribution, "attributedTo ${mutation.attributedTo} names no request row")
            assertEquals("/socket", byAttribution.endpoint)
            assertTrue(
                byAttribution.principal?.contains(user.toString()) == true,
                "the sub-socket's own row does not name the person: ${byAttribution.principal}",
            )

            val byRoot = requests.rowFor(mutation.rootExecutionId)
            assertNotNull(byRoot, "rootExecutionId ${mutation.rootExecutionId} names no request row")
            assertEquals("/multiplex", byRoot.endpoint)
            assertNull(
                byRoot.principal,
                "the root is the causal head, which here is the anonymous carrier; it must not be " +
                    "what an auditor reads to name the person",
            )
            assertTrue(
                byAttribution._id != byRoot._id,
                "the whole reason for two columns is that they can differ, and here they must",
            )
        }

    /**
     * The hardest case, and the one `attributedTo` exists for.
     *
     * A task has no request row of its own, so `requestId` is null and there is exactly one column
     * left to trace it by. That column must not be `rootExecutionId`: the root is the head of the
     * *causal* chain, which here is the multiplex carrier — an execution that authenticated nobody,
     * because the credential arrived on the sub-socket's own query parameters. Anchoring to the
     * causal head would therefore answer "anonymous" for a change a known person made.
     *
     * `attributedTo` anchors instead to the innermost execution that had a request row, and a task
     * inherits its launcher's anchor through the serialized `ExecutionCause` — so it survives the
     * queue, which is the only reason it works on a serverless engine at all.
     */
    @Test
    fun `a task launched from an authenticated sub-socket attributes to the person who opened it`() =
        probe { engine ->
            val physical = engine.openSocket(TestServer.multiplex, HttpHeaders.EMPTY)
            engine.startChannel(physical, "c1", tokenQuery())
            engine.sendOnChannel(physical, "c1", "task:${Uuid.random()}")
            engine.drainTasks()

            val mutation = mutations().single()
            assertEquals("task", mutation.initiatorKind)
            assertNull(mutation.requestId, "a task has no request row, so attributedTo is the only handle")

            val requests = requests()
            val byAttribution = requests.rowFor(mutation.attributedTo)
            assertNotNull(byAttribution, "attributedTo ${mutation.attributedTo} names no request row")
            assertEquals("/socket", byAttribution.endpoint, "the task inherited the sub-socket's anchor")
            assertTrue(
                byAttribution.principal?.contains(user.toString()) == true,
                "a change a person made through a multiplexed socket must name that person: " +
                    "${byAttribution.principal}",
            )

            // And the column that used to be the only handle still answers the causal question,
            // still lands on the carrier, and is still anonymous.
            val byRoot = requests.rowFor(mutation.rootExecutionId)
            assertNotNull(byRoot)
            assertEquals("/multiplex", byRoot.endpoint)
            assertNull(byRoot.principal)
        }

    /**
     * The minting bug underneath all of this: `subConnection` used to draw two separate ids for a
     * virtual socket's `executionId` and its `socketId`.
     *
     * A socket's request row is keyed by `socketId`, so with two ids the row was unreachable from the
     * execution id anything descending from the socket names — the sub-socket's own row existed and
     * nothing could find it. Observable here because a phase names its connect in `causedBy` while
     * the row is keyed by `socketId`: the two are the same id only if the connect minted one.
     */
    @Test
    fun `a virtual sub-socket's connect execution and its socket id are the same id`() = probe { engine ->
        val physical = engine.openSocket(TestServer.multiplex, authHeaders())
        engine.startChannel(physical, "c1")
        engine.sendOnChannel(physical, "c1", "direct:${Uuid.random()}")

        val mutation = mutations().single()
        val subRow = requests().single { it.endpoint == "/socket" }

        // subRow._id is the sub-socket's socketId; mutation.causedBy is its connect executionId.
        assertEquals(
            subRow._id,
            mutation.causedBy,
            "a sub-socket that mints separate connect and socket ids leaves its own request row " +
                "unreachable from everything descending from it",
        )
        assertEquals(subRow._id, mutation.attributedTo)
    }

    /**
     * What the root actually points at for a virtual socket, recorded because it is the fidelity the
     * join gives up: the row found is the *physical* connection's, whose endpoint is the multiplex
     * path, not the sub-socket's own row.
     */
    @Test
    fun `a virtual sub-socket gets its own request row, but the root names the physical connection`() =
        probe { engine ->
            val physical = engine.openSocket(TestServer.multiplex, authHeaders())
            engine.startChannel(physical, "c1")
            engine.sendOnChannel(physical, "c1", "direct:${Uuid.random()}")

            val requests = requests()
            val mutation = mutations().single()

            val subRow = requests.rowFor(mutation.requestId)
            assertNotNull(subRow, "the virtual sub-socket has no request row of its own")
            assertEquals("/socket", subRow.endpoint, "the sub-socket's row should name the sub-socket's endpoint")

            val rootRow = requests.rowFor(mutation.rootExecutionId)
            assertNotNull(rootRow)
            assertEquals(
                "/multiplex",
                rootRow.endpoint,
                "the root resolves to the physical connection, so the endpoint an auditor sees is the carrier",
            )
        }
}

// ===================== the server under test =====================

@Serializable
private data class SocketUser(override val _id: Uuid) : HasId<Uuid> {
    companion object : PrincipalType<SocketUser, Uuid> {
        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
        override val subjectSerializer: KSerializer<SocketUser> = serializer()

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): SocketUser = SocketUser(id)
    }
}

/**
 * The whole of the id as the credential, read from the Authorization header *or* the Authorization
 * query parameter.
 *
 * Both, because that is what the shipped reader does: `SessionManager.read` falls back to the
 * `Authorization` and `jwt` query parameters precisely because a browser cannot set headers on a
 * WebSocket. A reader that only looked at headers would make the query-parameter cases below
 * unconstructible for a reason that does not exist in the real server.
 */
private object TokenReader : Authentication.Reader<SocketUser> {
    override val priority: Double = 1.0

    context(server: ServerRuntime)
    override suspend fun read(request: Request<*>): Authentication<SocketUser>? {
        val raw = request.headers[HttpHeader.Authorization]?.root
            ?: request.queryParameters.find { it.first.equals(HttpHeader.Authorization, ignoreCase = true) }?.second
            ?: return null
        return SocketUser.testAuth(SocketUser(Uuid.parse(raw)))
    }
}

private object TestServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())

    val audit = path.path("audit") include AuditCore(database)
    val mutationLog = path.path("audit-mutation") include MutationLog(audit)

    init {
        registerBasicMediaTypeCoders()
        register(SocketUser)
        authReaders.register(TokenReader)
    }

    val patients = database.registerTable("Patient", Patient.serializer())

    /** Present only so the registry, which scans endpoint serializers, assigns Patient an id. */
    val sample = Patient(_id = Uuid.parse("00000000-0000-0000-0000-0000000000c2"), name = "", ssn = "")

    val patientEndpoint = path.path("patient").get bind ApiHttpHandler(
        summary = "Patient",
        auth = noAuth,
        implementation = { _: Unit -> sample },
    )

    /** The indirect path: the change happens in an execution of its own, with no request behind it. */
    val mutateTask: Task<Uuid> = path.path("mutate-task") bind Task(Uuid.serializer()) { id ->
        mutationLog.mutationLogged(patients()).insert(listOf(Patient(_id = id, name = "FromTask", ssn = "t")))
    }

    val socket = path.path("socket") bind WebSocketHandler<PathSpec0, Unit>(
        willConnect = { },
        messageFromClient = { frame ->
            val text = (frame as WebSocketFrame.Text).content
            val id = Uuid.parse(text.substringAfter(':'))
            when (text.substringBefore(':')) {
                "direct" -> mutationLog.mutationLogged(patients())
                    .insert(listOf(Patient(_id = id, name = "FromSocket", ssn = "s")))

                "task" -> mutateTask(id)
                else -> throw IllegalArgumentException("Unknown socket command: $text")
            }
        },
    )

    val multiplex = path.path("multiplex") bind MultiplexWebSocketHandler()
}

// ===================== the engine =====================

/**
 * An engine that drives sockets through the real interceptor chain and puts tasks through a real
 * queue.
 *
 * `TestRunner` cannot answer the question these tests ask. It runs tasks inline, inside the launching
 * execution, so a task there never becomes an execution of its own and would appear to inherit the
 * socket's attribution no matter what the engines actually record. The queue here serializes the
 * payload and drops every live object, as a Lambda invocation does, so what the task run sees is only
 * what was written into that payload — the same technique `TaskParentageTest` uses in core.
 *
 * Nothing here builds an [Initiator] the framework would not: the connect initiator is minted exactly
 * as every engine and `TestRunner` mints it, and every id after that is derived by `phase`,
 * `subConnection` or `executeWithMetrics`.
 */
@OptIn(InternalLightningServerApi::class)
private class ProbeEngine : EngineBase(TestServer.build()), ServerRuntime {
    override val serverId: String = "probe"
    override val serverVersion: String = "test"
    override val initiator: Initiator = Initiator.Direct(Uuid.random())

    override suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(
        event: WebSocketSubscriptionMessage<PATH, T>,
    ): Unit = Unit

    fun ready() {
        with(settings) {
            TestServer.database set Database.Settings()
            TestServer.cache set Cache.Settings()
        }
        settings.readyUsingDefaults()
    }

    /** Assigns the audit bit indices, as the deploy pipeline does before a version serves. */
    suspend fun preDeploy(): Unit = runPreDeployTasks()

    // ----- tasks, over a queue that keeps nothing but the payload -----

    @Serializable
    private data class Queued(val location: String, val cause: ExecutionCause?, val input: String)

    private val queue = ArrayDeque<Queued>()

    override suspend fun <T> Task<T>.invoke(input: T, cause: ExecutionCause?) {
        queue.addLast(
            Queued(
                location = location.toString(),
                cause = cause,
                input = internalSerialization.json.encodeToString(serializer, input),
            )
        )
    }

    suspend fun drainTasks() {
        while (queue.isNotEmpty()) {
            val queued = queue.removeFirst()
            val location = PathSpec0.fromString(queued.location)
            @Suppress("UNCHECKED_CAST")
            val task = server.tasks.getValue(location) as Task<Any?>
            task.executeWithMetrics(
                location,
                internalSerialization.json.decodeFromString(task.serializer, queued.input),
                queued.cause,
            )
        }
    }

    // ----- sockets -----

    inner class ProbeSocket<PATH : PathSpec, STORAGE>(
        private val handler: WebSocketHandler<PATH, STORAGE>,
        val request: WebSocketConnectRequest<PATH>,
        val initiator: Initiator.WebSocket,
        private var state: STORAGE,
    ) {
        val sent: MutableList<WebSocketFrame> = mutableListOf()

        val connection: WebSocketConnection<PATH, STORAGE> = object : WebSocketConnection<PATH, STORAGE> {
            override val request: WebSocketConnectRequest<PATH> get() = this@ProbeSocket.request
            override val currentState: STORAGE get() = state
            override suspend fun repullState(): STORAGE = state
            override suspend fun queueStateUpdate(modification: (STORAGE) -> STORAGE) {
                state = modification(state)
            }

            override suspend fun updateStateImmediately(modification: (STORAGE) -> STORAGE): STORAGE {
                state = modification(state)
                return state
            }

            override suspend fun subscribe(topic: WebSocketSubscriptionRequest<*, *>): Unit = Unit
            override suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>): Unit = Unit
            override suspend fun send(frame: WebSocketFrame) {
                sent += frame
            }

            override suspend fun close(reason: WebSocketClose): Unit = Unit
        }

        suspend fun send(text: String) {
            with(forExecution(initiator.phase(Initiator.WebSocket.Phase.ClientMessage))) {
                handler.messageFromClient(connection, WebSocketFrame.Text(text))
            }
        }
    }

    suspend fun <STORAGE> openSocket(
        handler: WebSocketHandler<PathSpec0, STORAGE>,
        headers: HttpHeaders,
    ): ProbeSocket<PathSpec0, STORAGE> {
        val intercepted = server.interceptIncomingSocket(handler)
        val request = WebSocketConnectRequest(
            path = RawWebSocketPath(handler.location),
            headers = headers,
            domain = "example.com",
            protocol = "wss",
            sourceIp = "10.0.0.9",
        )
        val socketId = generateRequestId()
        val initiator = Initiator.WebSocket(
            executionId = socketId,
            socketId = socketId,
            path = request.path,
            phase = Initiator.WebSocket.Phase.Connect,
        )
        val storage = with(forExecution(initiator)) { intercepted.willConnect(request) }
        return ProbeSocket(intercepted, request, initiator, storage).also {
            with(forExecution(initiator.phase(Initiator.WebSocket.Phase.Connected))) {
                intercepted.didConnect(it.connection)
            }
        }
    }

    /**
     * Opens a virtual socket onto `/socket` inside a physical multiplex connection.
     *
     * [queryParams] are the client's own, for the sub-socket only — the multiplex handler merges them
     * into the sub-connection's request, which is how a sub-socket can carry a credential the carrier
     * never saw.
     */
    suspend fun startChannel(
        physical: ProbeSocket<PathSpec0, *>,
        channel: String,
        queryParams: Map<String, List<String>>? = null,
    ) {
        physical.send(
            externalSerialization.json.encodeToString(
                MultiplexMessage(channel = channel, path = "/socket", start = true, queryParams = queryParams)
            )
        )
        failOnMultiplexError(physical)
    }

    suspend fun sendOnChannel(physical: ProbeSocket<PathSpec0, *>, channel: String, data: String) {
        physical.send(
            externalSerialization.json.encodeToString(MultiplexMessage(channel = channel, data = data))
        )
        failOnMultiplexError(physical)
    }

    /**
     * The multiplex handler answers a failed sub-socket with an error frame rather than throwing, so a
     * broken test would otherwise look like a passing one that simply recorded nothing.
     */
    private fun failOnMultiplexError(physical: ProbeSocket<PathSpec0, *>) {
        physical.sent.mapNotNull { (it as? WebSocketFrame.Text)?.content }
            .map { externalSerialization.json.decodeFromString<MultiplexMessage>(it) }
            .firstOrNull { it.error != null }
            ?.let { throw IllegalStateException("The multiplexed socket failed: ${it.error}") }
    }
}
