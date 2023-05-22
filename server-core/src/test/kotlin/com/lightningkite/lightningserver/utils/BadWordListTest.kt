package com.lightningkite.lightningserver.utils

import org.junit.Assert.*
import org.junit.Test

class BadWordListTest {
    @Test
    fun test() {
        assertTrue(BadWordList.detectParanoid("agodb"))
        assertFalse(BadWordList.detectParanoid("abcde"))
    }
}