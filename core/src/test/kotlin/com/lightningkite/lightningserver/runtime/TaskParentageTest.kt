package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Every initiator a task run under [QueueingEngine] saw, in the order the tasks ran. */
private val recorded = mutableListOf<Execution>()

private object TestServer : ServerBuilder() {
    val second: Task<Unit> = path.path("second") bind Task(Unit.serializer()) {
        recorded += serverRuntime.execution
    }
    val first: Task<Unit> = path.path("first") bind Task(Unit.serializer()) {
        recorded += serverRuntime.execution
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
    private data class Queued(val location: String, val from: Execution)

    private val queue = ArrayDeque<String>()

    override suspend fun <T> dispatchTask(task: Task<T>, input: T, from: Execution) {
        queue.addLast(Json.encodeToString(Queued.serializer(), Queued(task.location.toString(), from)))
    }

    /** Runs everything queued, reading each payload back the way a fresh process would. */
    suspend fun drain() {
        while (queue.isNotEmpty()) {
            val queued = Json.decodeFromString(Queued.serializer(), queue.removeFirst())
            val location = PathSpec0.fromString(queued.location)
            @Suppress("UNCHECKED_CAST")
            val task = server.tasks.getValue(location) as Task<Unit>
            task.executeInlineWithMetrics(location, Unit, queued.from)
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

        val requestId = with(engine) { Execution.ID.generate() }
        runBlocking {
            engine.executeWithoutTelemetry(
                Execution.Http(
                    id = requestId,
                    endpoint = RawHttpEndpoint<PathSpec>(asString = "/thing", method = HttpMethod.GET),
                )
            ) {
                TestServer.first(Unit)
            }
            // `first` queues `second` while it runs, so one drain covers both hops.
            engine.drain()
        }

        assertEquals(2, recorded.size, "Expected both tasks to run. Got: $recorded")

        val first = recorded[0] as Execution.Task
        assertEquals(requestId, first.causedBy, "The task should name the request that launched it.")
        assertEquals(requestId, first.rootExecution)
        assertNotEquals(requestId, first.id, "A task is its own execution, not the request's.")

        val second = recorded[1] as Execution.Task
        assertEquals(first.id, second.causedBy, "A task launched from a task names that task.")
        assertEquals(
            requestId,
            second.rootExecution,
            "The root must survive every hop, or \"everything caused by request X\" needs a recursive walk.",
        )
    }
}
