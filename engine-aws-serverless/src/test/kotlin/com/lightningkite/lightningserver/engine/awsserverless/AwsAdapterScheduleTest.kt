package com.lightningkite.lightningserver.engine.awsserverless

import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.services.cache.get
import com.lightningkite.services.cache.set
import com.lightningkite.services.cache.setIfNotExists
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class AwsAdapterScheduleTest {
    companion object {
        val runCount = AtomicInteger(0)
    }

    object SampleServer : ServerBuilder() {
        val tick = path.path("tick") bind ScheduledTask(frequency = 1.minutes) {
            runCount.incrementAndGet()
        }
    }

    @Test
    fun normalInvocationRunsAndReleasesLock() = runBlocking {
        runCount.set(0)
        val adapter = TestAwsAdapter(SampleServer.build())
        val input = adapter.server.schedules.entries.single().key.toString()
        val response = adapter.schedules.handleSchedule(AwsAdapterSchedule.Scheduled(input))
        assertEquals(200, response.statusCode)
        assertEquals(1, runCount.get(), "The scheduled task should have run exactly once")
        // Lock must be released after the tick so the next trigger can run.
        assertNull(adapter.cache.get<Boolean>("$input-lock"), "Lock should be released after execution")
    }

    @Test
    fun heldLockSkipsExecution() = runBlocking {
        runCount.set(0)
        val adapter = TestAwsAdapter(SampleServer.build())
        val input = adapter.server.schedules.entries.single().key.toString()
        // Simulate a concurrent invocation already holding the lock.
        assertEquals(true, adapter.cache.setIfNotExists("$input-lock", true))
        val response = adapter.schedules.handleSchedule(AwsAdapterSchedule.Scheduled(input))
        assertEquals(200, response.statusCode, "A skipped tick still reports benign success")
        assertEquals(0, runCount.get(), "The scheduled task must not run while the lock is held")
        // The pre-existing lock must not be cleared by the skipped invocation.
        assertEquals(true, adapter.cache.get<Boolean>("$input-lock"))
    }
}
