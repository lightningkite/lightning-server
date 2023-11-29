package com.lightningkite.lightningserver.core

import org.junit.Assert.*
import org.junit.Test

class ContentTypeTest {
    @Test
    fun test() {
        assertTrue(ContentType("application/pdf").parameters.isEmpty())
    }
}