package com.lightningkite.lightningdb

import com.lightningkite.trimmedCaseless
import kotlin.test.Test
import kotlin.test.assertNotEquals

class SerializablePropertiesTest {
    init {
        com.lightningkite.lightningdb.prepareModels()
    }
    @Test
    fun test() {
        assertNotEquals(listOf(), User_email.annotations.also { println(it) })
    }
}