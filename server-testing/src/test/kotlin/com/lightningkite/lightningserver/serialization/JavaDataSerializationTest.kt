package com.lightningkite.lightningserver.serialization

import com.lightningkite.UUID
import com.lightningkite.lightningserver.db.*
import com.lightningkite.lightningserver.db.test.*
import com.lightningkite.lightningserver.prepareModelsServerCore
import com.lightningkite.prepareModelsShared
import com.lightningkite.serialization.contextualSerializerIfHandled
import com.lightningkite.serialization.notNull
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

class JavaDataSerializationTest {
    init {
        prepareModelsShared()
        prepareModelsServerCore()
        prepareModelsServerTesting()
    }
    @Test fun test() {
        condition<LargeTestModel> { it.int.gt(5) and it.string.contains("asdf", true) or it.intNullable.notNull.gt(8) }.cycle()
        modification<LargeTestModel> {
            it.string assign "Sample"
            it.int += 5
            it.intNullable assign null
        }.cycle()
        Query(condition<LargeTestModel>() { it.int.gt(5) }, sort { it.long.ascending() }).cycle()
        Query(condition<LargeTestModel>() { it._id.eq(UUID.random()) }, sort { it.long.ascending() }).cycle()
    }
    inline fun <reified T> T.cycle() {
        val ser = Serialization.javaData.serializersModule.contextualSerializerIfHandled<T>()
        println(this)
        val reconstituted = Serialization.javaData.decodeFromBase64(ser, Serialization.javaData.encodeToBase64(ser, this).also { println(it) })
        assertEquals(this, reconstituted)
    }

    @Test fun quickCheck() {
        println(Serialization.json.encodeToString(byteArrayOf(1, 2, 3, 4, 5)))
    }
}