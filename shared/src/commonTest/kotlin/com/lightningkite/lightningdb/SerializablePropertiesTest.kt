package com.lightningkite.lightningserver.db

import com.lightningkite.*
import com.lightningkite.lightningserver.db.testing.User_email
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
        path<UUID>().eq(UUID.random())
    }
}