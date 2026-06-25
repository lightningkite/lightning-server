package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.modelRestTest
import kotlin.test.Test

class ModelRestTest {
    @Test
    fun `model rest endpoints insert get list modify delete all work`() { modelRestTest() }
}
