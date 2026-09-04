package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.InternalLightningServerApi
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
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.pathing.path
import com.lightningkite.lightningserver.runtime.EngineBase
import com.lightningkite.lightningserver.runtime.ExecutionCause
import com.lightningkite.lightningserver.runtime.Initiator
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.executeWithMetrics
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.invoke
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.MetaEndpoints
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
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
 * The same attribution question as [WebSocketMutationAttributionTest], with no socket involved.
 *
 * A `/meta/bulk` sub-request is structurally what a multiplexed sub-socket is: `HttpRequest.subRequest`
 * gives it its own query parameters while sharing the carrier's `cache`, and `Initiator.Http.subRequest`
 * gives it its own `executionId` while inheriting the carrier's `rootExecutionId`. So if a sub-request
 * can authenticate where the carrier did not, everything the socket tests found follows here too —
 * which is why the fix is not about sockets: `rootExecutionId` names the outermost execution, and the
 * outermost execution is not necessarily the one that authenticated anybody. `attributedTo` is the
 * column that answers "who", and these tests assert it alongside the root so the two stay distinct.
 */
@OptIn(InternalLightningServerApi::class)
class BulkSubRequestMutationAttributionTest {

    private val user = Uuid.parse("00000000-0000-4000-8000-0000000000d1")

    private fun probe(block: suspend context(ServerRuntime) (BulkProbeEngine) -> Unit) = runBlocking {
        val engine = BulkProbeEngine()
        engine.ready()
        engine.preDeploy()
        block(engine, engine)
    }

    context(server: ServerRuntime)
    private suspend fun requests() = BulkTestServer.audit.requests().find(Condition.Always).toList()

    context(server: ServerRuntime)
    private suspend fun mutations() = BulkTestServer.mutationLog.mutations().find(Condition.Always).toList()

    private fun List<RequestRecord>.rowFor(id: Uuid?): RequestRecord? = firstOrNull { it._id == id }

    /**
     * A bulk request carrying one sub-request, with no credential of its own.
     *
     * The credential rides in the sub-request's *own* query string, which is what the client controls
     * per sub-request: `MetaEndpoints` splits `path` on `?` and hands the result to
     * `HttpRequest.subRequest` as that sub-request's query parameters.
     */
    private fun bulkOf(subPath: String, subBody: String) = HttpRequest<PathSpec>(
        path = RawHttpEndpoint(asString = "/meta/bulk", method = HttpMethod.POST),
        queryParameters = QueryParameters.EMPTY,
        headers = HttpHeaders { add(HttpHeader.ContentType, MediaType.Application.Json.toString()) },
        domain = "example.com",
        protocol = "https",
        sourceIp = "10.0.0.2",
        body = TypedData.text(
            """{"only":{"path":"$subPath","method":"POST","body":${escape(subBody)}}}""",
            MediaType.Application.Json,
        ),
    )

    private fun escape(raw: String) = "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun credential() = "${HttpHeader.Authorization}=$user"

    // ===================== can a sub-request authenticate at all? =====================

    /**
     * `subRequest` hands the sub-request the carrier's same `SerializableCache`, so an anonymous
     * carrier resolving `Authentication.CacheKey` first could memoize a null the sub-request inherits.
     * It does not, for the same reason it does not for sub-sockets:
     * `SerializableCache.get(CalculatingKey, input)` is `retrieve(key)?.value ?: calculate(input)`, so
     * a cached null falls through to a fresh resolution against the sub-request's own query
     * parameters.
     */
    @Test
    fun `a bulk sub-request authenticates on its own query parameters even when the carrier did not`() =
        probe { engine ->
            val response = engine.handle(bulkOf("/mutate?${credential()}", Uuid.random().toString()), engine.newId())
            assertEquals(HttpStatus.OK, response.status)

            val requests = requests()
            val carrier = requests.single { it.endpoint == "/meta/bulk" }
            assertNull(carrier.principal, "the carrying bulk request was supposed to be anonymous")

            val sub = requests.single { it.endpoint == "/mutate" }
            assertTrue(
                sub.principal?.contains(user.toString()) == true,
                "the sub-request did not authenticate on its own query parameters: ${sub.principal}",
            )
        }

    // ===================== a direct mutation from a sub-request =====================

    /**
     * The two columns pulling apart, on one row, with no socket involved.
     *
     * `attributedTo` names the sub-request, which authenticated; `rootExecutionId` names the bulk
     * carrier, which did not. Pinned together so neither can later be collapsed into the other on the
     * grounds that they usually agree.
     */
    @Test
    fun `a direct mutation from an authenticated sub-request attributes to the person while its root stays anonymous`() =
        probe { engine ->
            val id = Uuid.random()
            engine.handle(bulkOf("/mutate?${credential()}", id.toString()), engine.newId())

            val requests = requests()
            val mutation = mutations().single()
            assertEquals(id.toString(), mutation.recordId)

            val byAttribution = requests.rowFor(mutation.attributedTo)
            assertNotNull(byAttribution, "attributedTo ${mutation.attributedTo} names no request row")
            assertEquals("/mutate", byAttribution.endpoint, "attributedTo should name the sub-request's own row")
            assertTrue(
                byAttribution.principal?.contains(user.toString()) == true,
                "the sub-request's own row does not name the person: ${byAttribution.principal}",
            )

            val byRoot = requests.rowFor(mutation.rootExecutionId)
            assertNotNull(byRoot, "rootExecutionId ${mutation.rootExecutionId} names no request row")
            assertEquals("/meta/bulk", byRoot.endpoint, "the root should be the carrying bulk request")
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
     * The asymmetry with a sub-socket, and the one that matters for a fix: HTTP has no third
     * identifier. `Initiator.requestRecordId` is `(this as? Initiator.WebSocket)?.socketId ?: executionId`,
     * so a sub-request's row is keyed by its own `executionId` — the id the mutation already carries
     * in `executionId` as well as in `requestId`.
     */
    @Test
    fun `a bulk sub-request's row is keyed by its own executionId`() = probe { engine ->
        engine.handle(bulkOf("/mutate?${credential()}", Uuid.random().toString()), engine.newId())

        val mutation = mutations().single()
        assertEquals(
            mutation.executionId,
            mutation.requestId,
            "an HTTP execution's request row is keyed by the execution itself, unlike a socket's",
        )
        assertEquals(
            mutation.executionId,
            mutation.attributedTo,
            "an execution that has a request row of its own anchors to that row",
        )
        assertNotNull(requests().rowFor(mutation.executionId))
    }

    // ===================== the gap, with no socket involved =====================

    /**
     * The socket file's hardest case, reached through plain HTTP — which is what makes this a
     * framework-wide property rather than a websocket quirk.
     *
     * A task has no request row of its own, so `requestId` is null and there is exactly one column
     * left to trace it by. That column must not be `rootExecutionId`: the root is the head of the
     * *causal* chain, which here is the bulk carrier — an execution that authenticated nobody,
     * because the credential arrived on the sub-request's own query parameters. Anchoring to the
     * causal head would answer "anonymous" for a change a known person made.
     *
     * `attributedTo` anchors to the innermost execution that had a request row, and a task inherits
     * its launcher's anchor through the serialized `ExecutionCause`, so it survives the queue.
     */
    @Test
    fun `a task launched from an authenticated bulk sub-request attributes to the person who made it`() =
        probe { engine ->
            val id = Uuid.random()
            engine.handle(bulkOf("/mutate-task?${credential()}", id.toString()), engine.newId())
            engine.drainTasks()

            val mutation = mutations().single()
            assertEquals(id.toString(), mutation.recordId)
            assertEquals("task", mutation.initiatorKind, "the task ran inline rather than as its own execution")
            assertNull(mutation.requestId, "a task has no request row, so attributedTo is the only handle")

            val requests = requests()
            val byAttribution = requests.rowFor(mutation.attributedTo)
            assertNotNull(byAttribution, "attributedTo ${mutation.attributedTo} names no request row")
            assertEquals("/mutate-task", byAttribution.endpoint, "the task inherited the sub-request's anchor")
            assertTrue(
                byAttribution.principal?.contains(user.toString()) == true,
                "a change a person made through a bulk sub-request must name that person: " +
                    "${byAttribution.principal}",
            )

            // And the column that used to be the only handle still answers the causal question,
            // still lands on the carrier, and is still anonymous.
            val byRoot = requests.rowFor(mutation.rootExecutionId)
            assertNotNull(byRoot)
            assertEquals("/meta/bulk", byRoot.endpoint)
            assertNull(byRoot.principal)
        }

    /** The control: with the credential on the carrier, the very same task traces back fine. */
    @Test
    fun `a task launched from a bulk sub-request traces back when the carrier itself is authenticated`() =
        probe { engine ->
            val id = Uuid.random()
            val request = bulkOf("/mutate-task", id.toString()).let {
                HttpRequest<PathSpec>(
                    path = it.path,
                    queryParameters = it.queryParameters,
                    headers = HttpHeaders {
                        add(HttpHeader.ContentType, MediaType.Application.Json.toString())
                        add(HttpHeader.Authorization, user.toString())
                    },
                    domain = it.domain,
                    protocol = it.protocol,
                    sourceIp = it.sourceIp,
                    body = it.body,
                )
            }
            engine.handle(request, engine.newId())
            engine.drainTasks()

            val mutation = mutations().single()
            val requests = requests()
            val byRoot = requests.rowFor(mutation.rootExecutionId)
            assertNotNull(byRoot)
            assertTrue(
                byRoot.principal?.contains(user.toString()) == true,
                "an authenticated carrier should make the whole tree attributable: ${byRoot.principal}",
            )
            // With the credential on the carrier the two questions have the same answer, which is
            // why a single column looked sufficient until an inner execution held the credential.
            val byAttribution = requests.rowFor(mutation.attributedTo)
            assertNotNull(byAttribution)
            assertTrue(byAttribution.principal?.contains(user.toString()) == true)
        }
}

// ===================== the server under test =====================

@Serializable
private data class BulkUser(override val _id: Uuid) : HasId<Uuid> {
    companion object : PrincipalType<BulkUser, Uuid> {
        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
        override val subjectSerializer: KSerializer<BulkUser> = serializer()

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): BulkUser = BulkUser(id)
    }
}

/** Header or query parameter, as `SessionManager.read` does. */
private object BulkTokenReader : Authentication.Reader<BulkUser> {
    override val priority: Double = 1.0

    context(server: ServerRuntime)
    override suspend fun read(request: Request<*>): Authentication<BulkUser>? {
        val raw = request.headers[HttpHeader.Authorization]?.root
            ?: request.queryParameters.find { it.first.equals(HttpHeader.Authorization, ignoreCase = true) }?.second
            ?: return null
        return BulkUser.testAuth(BulkUser(Uuid.parse(raw)))
    }
}

private object BulkTestServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())

    val audit = path.path("audit") include AuditCore(database)
    val mutationLog = path.path("audit-mutation") include MutationLog(audit)

    init {
        registerBasicMediaTypeCoders()
        register(BulkUser)
        authReaders.register(BulkTokenReader)
    }

    val patients = database.registerTable("Patient", Patient.serializer())

    /** Present only so the registry, which scans endpoint serializers, assigns Patient an id. */
    val sample = Patient(_id = Uuid.parse("00000000-0000-0000-0000-0000000000d2"), name = "", ssn = "")

    val patientEndpoint = path.path("patient").get bind ApiHttpHandler(
        summary = "Patient",
        auth = noAuth,
        implementation = { _: Unit -> sample },
    )

    val mutateTask: Task<Uuid> = path.path("mutate-task-impl") bind Task(Uuid.serializer()) { id ->
        mutationLog.mutationLogged(patients()).insert(listOf(Patient(_id = id, name = "FromTask", ssn = "t")))
    }

    /** POST so the record id travels in the body, leaving the query string to the credential. */
    val mutate = path.path("mutate").post bind ApiHttpHandler(
        summary = "Mutate",
        auth = noAuth,
        implementation = { id: Uuid ->
            mutationLog.mutationLogged(patients()).insert(listOf(Patient(_id = id, name = "FromRequest", ssn = "s")))
            "ok"
        },
    )

    val mutateViaTask = path.path("mutate-task").post bind ApiHttpHandler(
        summary = "Mutate Via Task",
        auth = noAuth,
        implementation = { id: Uuid ->
            mutateTask(id)
            "ok"
        },
    )

    val meta = path.path("meta") include MetaEndpoints(
        packageName = "com.lightningkite.lightningserver.audit",
        database = database,
        cache = cache,
    )
}

// ===================== the engine =====================

/**
 * The HTTP twin of `WebSocketMutationAttributionTest`'s probe engine: everything is the framework's
 * own `handle`, except that tasks go over a queue that keeps only the serialized payload, so a task
 * really becomes an execution of its own instead of running inline in its launcher.
 */
@OptIn(InternalLightningServerApi::class)
private class BulkProbeEngine : EngineBase(BulkTestServer.build()), ServerRuntime {
    override val serverId: String = "bulk-probe"
    override val serverVersion: String = "test"
    override val initiator: Initiator = Initiator.Direct(Uuid.random())

    override suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(
        event: WebSocketSubscriptionMessage<PATH, T>,
    ): Unit = Unit

    fun ready() {
        with(settings) {
            BulkTestServer.database set Database.Settings()
            BulkTestServer.cache set Cache.Settings()
        }
        settings.readyUsingDefaults()
    }

    suspend fun preDeploy(): Unit = runPreDeployTasks()

    /** The id an engine would mint for an incoming request. */
    fun newId(): Uuid = com.lightningkite.lightningserver.http.generateRequestId()

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
}
