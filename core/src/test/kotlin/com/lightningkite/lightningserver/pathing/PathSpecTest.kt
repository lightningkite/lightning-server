package com.lightningkite.lightningserver.pathing

import org.junit.Assert.assertEquals
import kotlin.test.Test

class PathSpecTest {
    @Test
    fun stringInOut() {
        assertEquals("/test/task", PathSpec0.fromString("/test/task").toString())
    }
}