package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.scheduleTest
import kotlin.test.Test

class SchedulesTest {
    @Test
    fun `scheduled task body executes and effect is observable`() { scheduleTest() }
}
