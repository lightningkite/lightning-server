package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.taskTest
import kotlin.test.Test

class TasksTest {
    @Test
    fun `task executes inline in test runner and effect is immediately visible`() { taskTest() }
}
