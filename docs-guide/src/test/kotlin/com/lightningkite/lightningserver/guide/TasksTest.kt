package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.tasksLaunchTest
import com.lightningkite.lightningserver.guide.samples.tasksLifecycleTest
import kotlin.test.Test

class TasksTest {
    @Test fun `launched task runs inline in tests`() { tasksLaunchTest() }
    @Test fun `pre-deploy and startup tasks run`() { tasksLifecycleTest() }
}
