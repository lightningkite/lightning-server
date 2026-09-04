package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.Engine
import com.lightningkite.lightningserver.runtime.ExecutionCause
import com.lightningkite.lightningserver.runtime.EngineBase
import com.lightningkite.lightningserver.runtime.Initiator
import com.lightningkite.lightningserver.runtime.forExecution
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

private object EmptyServer : ServerBuilder()

private class BareEngine : EngineBase(EmptyServer.build()) {
    override val serverId: String = "bare"
    override val serverVersion: String = "test"

    override suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(
        event: WebSocketSubscriptionMessage<PATH, T>,
    ): Nothing = throw NotImplementedError()

    override suspend fun <T> Task<T>.invoke(input: T, cause: ExecutionCause?): Nothing =
        throw NotImplementedError()
}

/**
 * [Runtime.Cached] must cache at engine scope even when it is resolved from inside an execution.
 *
 * Resolving a [Runtime] is process-wide work — settings, serializer modules, validators — so it is
 * keyed on the engine. But the context actually in scope inside a handler is a per-execution
 * runtime, freshly minted for every request, so keying on whatever was handed in silently recomputes
 * on every call. Nothing else catches this: the cache stays correct, it just stops being a cache,
 * and the only symptom is the work being redone. `ApiHttpHandler` resolves the annotation validators
 * through exactly this path on every typed request.
 */
@OptIn(InternalLightningServerApi::class)
class RuntimeCachedScopeTest {
    @Test
    fun `cached resolves once across many executions`() {
        var computations = 0
        val cached = Runtime.Cached(Runtime { computations++ })
        val engine: Engine = BareEngine()

        with(engine) { cached() }
        repeat(5) { with(engine.forExecution(Initiator.Direct(Uuid.random()))) { cached() } }

        assertEquals(1, computations, "a process-wide value was recomputed per execution")
    }

    @Test
    fun `an execution sees what the engine already resolved`() {
        var computations = 0
        val cached = Runtime.Cached(Runtime { computations++ })
        val engine: Engine = BareEngine()

        val fromEngine = with(engine) { cached() }
        val fromExecution = with(engine.forExecution(Initiator.Direct(Uuid.random()))) { cached() }

        assertEquals(fromEngine, fromExecution)
        assertEquals(1, computations)
    }

    /** Two engines are genuinely different scopes, so the cache must not leak between them. */
    @Test
    fun `separate engines resolve separately`() {
        var computations = 0
        val cached = Runtime.Cached(Runtime { computations++ })

        with(BareEngine()) { cached() }
        with(BareEngine()) { cached() }

        assertEquals(2, computations)
    }
}
