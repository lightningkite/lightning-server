package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.demo.endpoints.ProcessDataTaskInput
import com.lightningkite.lightningserver.demo.endpoints.SendEmailTaskInput
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.database.Database
import com.lightningkite.services.email.EmailService
import com.lightningkite.services.email.TestEmailService
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskExamplesEndpointsTest {

    /** TestRunner.Task.invoke executes tasks inline, so effects are observable right after .test(). */
    private fun taskTest(action: suspend context(TestRunner<Server>) Server.() -> Unit) =
        Server.testBlocking(
            settings = {
                database set Database.Settings("ram")
                email set EmailService.Settings("test")
            },
            action = action,
        )

    @Test
    fun enqueueEmailTaskActuallySendsAnEmail() = taskTest {
        val response = Server.taskExamples.enqueueEmailTask.test(
            null,
            SendEmailTaskInput(to = "someone@example.com", subject = "Hi", body = "Hello there")
        )

        assertEquals("send-email", response.taskType)
        val sent = (Server.email() as TestEmailService).lastEmailTo("someone@example.com")
        assertEquals("Hi", sent?.subject)
    }

    @Test
    fun enqueueProcessingTaskProcessesEveryItem() = taskTest {
        val response = Server.taskExamples.enqueueProcessingTask.test(
            null,
            ProcessDataTaskInput(items = listOf("a", "b", "c"))
        )

        assertEquals("process-data", response.taskType)
    }

    @Test
    fun triggerBackgroundTaskRunsViaTaskLaunchNotGlobalScope() = taskTest {
        val response = Server.taskExamples.triggerBackgroundTask.test()

        assertEquals(HttpStatus.OK, response.status)
        assertTrue(response.body?.text()?.contains("Background task #") == true)
    }

    @Test
    fun scheduledTaskStatusReflectsCacheState() = taskTest {
        val result = Server.taskExamples.scheduledTaskStatus.test(null, Unit)

        // Nothing has run the scheduled jobs yet in this test, so both should report no last run.
        assertEquals(null, result.cleanup.lastRunTimestamp)
        assertEquals(null, result.healthCheck.lastRunTimestamp)
    }
}
