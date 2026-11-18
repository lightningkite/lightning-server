package com.lightningkite.lightningserver.engine.local

import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.services.cache.get
import com.lightningkite.services.cache.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ScheduleFailureTest {

    object TestServer : ServerBuilder() {
        val runs = AtomicInteger(0)
        val failing = path.path("failing") bind ScheduledTask(frequency = 1.hours) {
            runs.incrementAndGet()
            throw IllegalStateException("tick failed")
        }
    }

    class TestEngine(server: ServerDefinition) : LocalEngine(server) {
        override val scope: CoroutineScope = CoroutineScope(Job())
        fun startSchedulesForTest() = startSchedules()
        fun shutdownForTest() = gracefulShutdown(1.seconds) {}
    }

    @Test
    fun failed_tick_waits_for_the_next_scheduled_run() {
        val engine = TestEngine(TestServer.build())
        engine.settings.readyUsingDefaults()
        val nextRunKey = "${engine.server.schedules.keys.single()}-nextRun"
        runBlocking { engine.cache.set<Long>(nextRunKey, 0L) }

        engine.startSchedulesForTest()
        val nextRun = try {
            val deadline = System.currentTimeMillis() + 5_000
            while (TestServer.runs.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            // Long enough for a failing tick that never advanced nextRun to have rerun many times over.
            Thread.sleep(1_500)
            runBlocking { engine.cache.get<Long>(nextRunKey) }
        } finally {
            engine.shutdownForTest()
        }

        assertEquals(1, TestServer.runs.get(), "a failed tick must not be retried before its next scheduled run")
        assertNotNull(nextRun)
        assertTrue(nextRun > System.currentTimeMillis() + 59.minutes.inWholeMilliseconds, "nextRun should be about an hour out")
    }
}
