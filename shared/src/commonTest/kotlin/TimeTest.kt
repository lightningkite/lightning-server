package com.lightningkite.lightningdb

import com.lightningkite.OffsetDateTime
import com.lightningkite.ZonedDateTime
import com.lightningkite.nowLocal
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeTest {
    @Test fun testZoned() {
        val x = nowLocal()
        assertEquals(x, ZonedDateTime.parse(x.toString().also { println(it) }))
    }
    @Test fun testOffset() {
        val x = nowLocal().toOffsetDateTime()
        assertEquals(x, OffsetDateTime.parse(x.toString().also { println(it) }))
    }
}