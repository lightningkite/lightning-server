package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.pathing.path
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

/** Every initiator a task run under [QueueingEngine] saw, in the order the tasks ran. */
private val recorded = mutableListOf<Initiator>()

private object TestServer : ServerBuilder() {
    val second: Task<Unit> = path.path("second") bind Task(Unit.serializer()) {
        recorded += serverRuntime.initiator
    }
    val first: Task<Unit> = path.path("first") bind Task(Unit.serializer()) {
        recorded += serverRuntime.initiator
        second(Unit)
    }
}

/**
 * An engine whose task queue is a real queue: launching serializes the payload to a string and drops
 * every live object, the way a Lambda invocation does. Nothing reaches the task run except what was
 * written into that string.
 *
 * This is the only way to test parentage honestly. `TestRunner` runs tasks inline for determinism, so
 * a task there never leaves the launching execution and would appear to inherit parentage no matter
 * what the engines actually serialize.
 */
@OptIn(InternalLightningServerApi::class)
private class QueueingEngine : EngineBase(TestServer.build()) {
    override val serverId: String = "queueing"
    override val serverVersion: String = "test"

    override suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(
        event: WebSocketSubscriptionMessage<PATH, T>,
    ): Nothing = throw NotImplementedError()

    /** The queued form. Tasks here take [Unit], so parentage is the whole of the payload. */
    @Serializable
    private data class Queued(val location: String, val cause: ExecutionCause?)

    private val queue = ArrayDeque<String>()

    override suspend fun <T> Task<T>.invoke(input: T, cause: ExecutionCause?) {
        queue.addLast(Json.encodeToString(Queued.serializer(), Queued(location.toString(), cause)))
    }

    /** Runs everything queued, reading each payload back the way a fresh process would. */
    suspend fun drain() {
        while (queue.isNotEmpty()) {
            val queued = Json.decodeFromString(Queued.serializer(), queue.removeFirst())
            val location = PathSpec0.fromString(queued.location)
            @Suppress("UNCHECKED_CAST")
            val task = server.tasks.getValue(location) as Task<Unit>
            task.executeWithMetrics(location, Unit, queued.cause)
        }
    }
}

/**
 * A task launched from an execution must be able to name that execution after crossing a queue —
 * the one thing §2.2 of the refactor makes the initiator serializable for.
 */
@OptIn(InternalLightningServerApi::class)
class TaskParentageTest {
    @Test
    fun `parentage survives the queue, and the root survives two hops`() {
        recorded.clear()
        val engine = QueueingEngine()
        with(engine) { settings.readyUsingDefaults() }

        val requestId = Uuid.random()
        runBlocking {
            val request = engine.forExecution(
                Initiator.Http(
                    executionId = requestId,
                    endpoint = RawHttpEndpoint<PathSpec>(asString = "/thing", method = HttpMethod.GET),
                )
            )
            with(request) { TestServer.first(Unit) }
            // `first` queues `second` while it runs, so one drain covers both hops.
            engine.drain()
        }

        assertEquals(2, recorded.size, "Expected both tasks to run. Got: $recorded")

        val first = recorded[0] as Initiator.Task
        assertEquals(requestId, first.causedBy, "The task should name the request that launched it.")
        assertEquals(requestId, first.rootExecutionId)
        assertNotEquals(requestId, first.executionId, "A task is its own execution, not the request's.")

        val second = recorded[1] as Initiator.Task
        assertEquals(first.executionId, second.causedBy, "A task launched from a task names that task.")
        assertEquals(
            requestId,
            second.rootExecutionId,
            "The root must survive every hop, or \"everything caused by request X\" needs a recursive walk.",
        )
    }

    @Test
    fun `a task with nothing behind it heads its own chain`() {
        recorded.clear()
        val engine = QueueingEngine()
        with(engine) { settings.readyUsingDefaults() }

        runBlocking {
            with(engine) { TestServer.second.invoke(Unit, cause = null) }
            engine.drain()
        }

        val only = recorded.single() as Initiator.Task
        assertEquals(null, only.causedBy)
        assertEquals(only.executionId, only.rootExecutionId)
    }
}
