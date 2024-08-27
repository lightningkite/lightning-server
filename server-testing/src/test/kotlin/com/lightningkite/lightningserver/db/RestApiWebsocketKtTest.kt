package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import com.lightningkite.lightningdb.test.*
import org.junit.Test
import kotlin.test.assertEquals

class RestApiWebsocketKtTest {
    @Test
    fun relevant() {
        assertEquals(
            setOf(1.hashCode()),
            condition<LargeTestModel> { it.int eq 1 }.relevantHashCodesForKey(LargeTestModel_int)
        )
        assertEquals(
            setOf(1.hashCode()),
            condition<LargeTestModel> { (it.int eq 1) and (it.boolean eq false) }.relevantHashCodesForKey(LargeTestModel_int)
        )
        assertEquals(
            setOf(1.hashCode(), 2.hashCode(), 3.hashCode()),
            condition<LargeTestModel> { it.int inside listOf(1, 2, 3) }.relevantHashCodesForKey(LargeTestModel_int)
        )
        assertEquals(
            setOf(1.hashCode(), 2.hashCode(), 3.hashCode()),
            condition<LargeTestModel> { (it.int eq 1) or (it.int eq 2) or (it.int eq 3) }.relevantHashCodesForKey(LargeTestModel_int)
        )
        assertEquals(
            null,
            condition<LargeTestModel> { it.short eq 1 }.relevantHashCodesForKey(LargeTestModel_int)
        )
        assertEquals(
            null,
            condition<LargeTestModel> { (it.int eq 1) or (it.short eq 1) }.relevantHashCodesForKey(LargeTestModel_int)
        )
    }
}