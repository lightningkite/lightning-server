package com.lightningkite.lightningserver.compression

import org.junit.Assert.*
import org.junit.Test

class EngineCompressionKtTest {
    @Test
    fun test() {
        val source = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
        assertArrayEquals(source, source.gzip().ungzip())
    }
}