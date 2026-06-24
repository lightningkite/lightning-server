package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.buildServer
import kotlin.test.Test

class RunningTest {
    @Test
    fun `ServerBuilder build produces a valid ServerDefinition`() { buildServer() }
}
