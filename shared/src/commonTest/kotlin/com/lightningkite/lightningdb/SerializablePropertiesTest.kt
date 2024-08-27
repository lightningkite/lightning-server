package com.lightningkite.lightningdb

import com.lightningkite.*
import com.lightningkite.lightningdb.testing.User_email
import kotlin.test.Test
import kotlin.test.assertNotEquals

class SerializablePropertiesTest {
    init {
        prepareModelsShared()
        prepareModelsSharedTest()
    }
    @Test
    fun test() {
        assertNotEquals(listOf(), User_email.annotations.also { println(it) })
        path<UUID>().eq(uuid())
    }
}