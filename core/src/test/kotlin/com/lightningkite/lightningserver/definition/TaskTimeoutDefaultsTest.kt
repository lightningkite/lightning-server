package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.data.Schedule
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

class TaskTimeoutDefaultsTest {

    @Test
    fun `Task interface default timeout should be 5 minutes`() {
        val task = object : Task<String> {
            override val serializer = String.serializer()
            // Don't override timeout - use default

            context(server: com.lightningkite.lightningserver.runtime.ServerRuntime)
            override suspend fun executeInline(input: String) {
            }
        }

        assertEquals(5.minutes, task.timeout, "Task interface default timeout should be 5 minutes")
    }

    @Test
    fun `Task factory function default timeout should be 5 minutes`() {
        val task = Task<String> { }

        assertEquals(5.minutes, task.timeout, "Task factory default timeout should be 5 minutes")
    }

    @Test
    fun `ScheduledTask interface default timeout should be 5 minutes`() {
        val task = object : ScheduledTask {
            override val schedule = Schedule.Frequency(1.minutes)
            // Don't override timeout - use default

            context(server: com.lightningkite.lightningserver.runtime.ServerRuntime)
            override suspend fun execute() {
            }
        }

        assertEquals(5.minutes, task.timeout, "ScheduledTask interface default timeout should be 5 minutes")
    }

    @Test
    fun `ScheduledTask factory function with Schedule default timeout should be 5 minutes`() {
        val task = ScheduledTask(Schedule.Frequency(1.minutes)) { }

        assertEquals(5.minutes, task.timeout, "ScheduledTask factory default timeout should be 5 minutes")
    }

    @Test
    fun `ScheduledTask factory function with frequency default timeout should be 5 minutes`() {
        val task = ScheduledTask(1.minutes) { }

        assertEquals(5.minutes, task.timeout, "ScheduledTask frequency factory default timeout should be 5 minutes")
    }

    @Test
    fun `ScheduledTask factory function with daily default timeout should be 5 minutes`() {
        val task = ScheduledTask(
            LocalTime(12, 0),
            TimeZone.UTC
        ) { }

        assertEquals(5.minutes, task.timeout, "ScheduledTask daily factory default timeout should be 5 minutes")
    }

    @Test
    fun `StartupTask interface default timeout should be 5 minutes`() {
        val task = object : StartupTask {
            // Don't override timeout - use default

            context(server: com.lightningkite.lightningserver.runtime.ServerRuntime)
            override suspend fun execute() {
            }
        }

        assertEquals(5.minutes, task.timeout, "StartupTask interface default timeout should be 5 minutes")
    }

    @Test
    fun `StartupTask factory function default timeout should be 5 minutes`() {
        val task = StartupTask { }

        assertEquals(5.minutes, task.timeout, "StartupTask factory default timeout should be 5 minutes")
    }

    @Test
    fun `all task types should have consistent 5 minute default timeout`() {
        val task = Task<String> { }
        val scheduledTask = ScheduledTask(1.minutes) { }
        val startupTask = StartupTask { }

        assertEquals(task.timeout, scheduledTask.timeout, "Task and ScheduledTask should have same default timeout")
        assertEquals(task.timeout, startupTask.timeout, "Task and StartupTask should have same default timeout")
        assertEquals(5.minutes, task.timeout, "All tasks should default to 5 minutes")
    }
}
