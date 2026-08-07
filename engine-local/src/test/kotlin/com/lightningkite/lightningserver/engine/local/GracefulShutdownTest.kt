package com.lightningkite.lightningserver.engine.local

import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.services.Service
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.HealthStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.builtins.serializer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Unit test for the shared [LocalEngine.gracefulShutdown] / schedule-cancellation logic. Bypasses
 * sockets entirely: drives the lifecycle directly through a minimal test engine and asserts via
 * observable behavior (fake-service disconnected, drain callback ran, engine scope cancelled).
 */
class GracefulShutdownTest {

    /** Records whether [disconnect] was called so the test can assert shutdown disconnects services. */
    class FakeService(override val context: SettingContext) : Service {
        override val name: String = "fake-service"
        val disconnected = AtomicBoolean(false)
        override suspend fun disconnect() {
            disconnected.set(true)
        }

        override suspend fun healthCheck(): HealthStatus = HealthStatus(HealthStatus.Level.OK)
    }

    object TestServer : ServerBuilder() {
        /** Captures the single constructed fake service so the test can inspect its disconnect flag. */
        val lastConstructed = AtomicReference<FakeService?>(null)

        // A setting whose runtime goal is a Service; gracefulShutdown should disconnect it.
        val fake = setting(
            name = "fake-service",
            default = Unit,
            serializer = Unit.serializer(),
        ) { FakeService(this).also { lastConstructed.set(it) } }
    }

    /** Minimal engine exposing the lifecycle for direct testing without reaching into protected state. */
    class TestEngine(server: ServerDefinition) : LocalEngine(server) {
        override val scope: CoroutineScope = CoroutineScope(Job())

        fun startSchedulesForTest() = startSchedules()
        fun shutdownForTest(drainRan: AtomicInteger) {
            gracefulShutdown(1.seconds) { drainRan.incrementAndGet() }
        }

        /** Observable: whether the engine scope's Job has been cancelled (schedules halted). */
        fun scopeCancelled(): Boolean = scope.coroutineContext[Job]?.isCancelled == true
        fun scopeActive(): Boolean = scope.coroutineContext[Job]?.isActive == true
    }

    private fun build(): TestEngine {
        TestServer.lastConstructed.set(null)
        val engine = TestEngine(TestServer.build())
        engine.settings.run {
            generalSettings.useDefault()
            secretBasis.useDefault()
            telemetrySettings.useDefault()
            loggingSettings.useDefault()
            enginePubSub.useDefault()
            engineCache.useDefault()
            forceWebSocketPubSub.useDefault()
            com.lightningkite.lightningserver.websockets.websocketSettings.useDefault()
            TestServer.fake.useDefault()
        }
        engine.settings.readyUsingDefaults()
        return engine
    }

    @Test
    fun shutdown_disconnects_services_runs_drain_and_cancels_scope() {
        val engine = build()
        engine.startSchedulesForTest()
        assertTrue(engine.scopeActive(), "scope job should be active before shutdown")

        val drainRan = AtomicInteger(0)
        engine.shutdownForTest(drainRan)

        assertEquals(1, drainRan.get(), "drainInFlight callback should run exactly once")
        // gracefulShutdown iterates allGoals(), which constructs the fake service and disconnects it.
        val fake = TestServer.lastConstructed.get()
        assertTrue(fake != null, "fake service goal should have been constructed during shutdown")
        assertTrue(fake.disconnected.get(), "fake service should be disconnected on shutdown")
        assertTrue(engine.scopeCancelled(), "engine scope job should be cancelled, halting schedules")
    }

    @Test
    fun shutdown_is_idempotent() {
        val engine = build()
        engine.startSchedulesForTest()
        val drainRan = AtomicInteger(0)
        engine.shutdownForTest(drainRan)
        engine.shutdownForTest(drainRan)
        assertEquals(1, drainRan.get(), "second shutdown must be a no-op")
    }
}
