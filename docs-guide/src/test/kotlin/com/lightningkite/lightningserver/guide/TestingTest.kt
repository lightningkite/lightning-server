package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.guide.samples.*
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class TestingTest {
    @Test fun `greets by name`() { TestingGreetTest().greetsByName() }
    @Test fun `rejects blank name`() { TestingGreetTest().rejectsBlankName() }
    @Test fun `settings lambda configures services`() { testingSettingsTest() }
    @Test fun `plain handler test`() { testingPlainHandlerTest() }
    @Test fun `authenticated typed endpoint with testAuth`() { testingAuthTest() }
    @Test fun `raw request goes through the HTTP pipeline`() { testingWireTest() }
    @Test fun `test clock controls now`() { testingClockTest() }
    @Test fun `build check passes for a valid server`() { testingBuildCheckTest() }

    @Test fun `build check in a module fails the whole server's build`() {
        val bad = object : ServerBuilder() {
            init {
                path.path("checked") include LockCheckedServer
            }
            val slow = path.path("slow") bind ScheduledTask(frequency = 5.minutes, timeout = 2.hours) {}
        }
        assertFailsWith<IllegalStateException> { bad.build() }
    }
}
