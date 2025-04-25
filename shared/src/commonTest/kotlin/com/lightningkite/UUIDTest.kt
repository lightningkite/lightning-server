package com.lightningkite

import kotlin.test.Test
import kotlin.test.assertEquals

class UUIDTest {
    @OptIn(ExperimentalStdlibApi::class)
    @Test fun testBytes() {
        val uuid = UUID.random()
        println(uuid.toByteArray().toHexString())
        println(uuid.toString())
        assertEquals(uuid, UUID.fromByteArray(uuid.toByteArray()))
    }
}