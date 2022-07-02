package com.lightningkite.lightningdb

import com.lightningkite.lightningdb.application.*
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReferenceFieldTests {


//    @Test
//    fun testReferenceFields() {
//        val condition = startChain<LargeTestModel>().list eq listOf()
//        assertTrue(condition.referencesField(LargeTestModel.::list))
//        assertFalse(condition.referencesField(LargeTestModel.::int))
//
//        val modification = startChain<LargeTestModel>().list assign listOf()
//        assertTrue(modification.referencesField(LargeTestModel.::list))
//        assertFalse(modification.referencesField(LargeTestModel.::int))
//    }
//
//    @Test
//    fun extraction() {
//        val condition = (startChain<LargeTestModel>().string eq "asdf") and (startChain<LargeTestModel>().int inside listOf(
//            1,
//            2,
//            3
//        ) or (startChain<LargeTestModel>().int eq 4))
//        val extracted = condition.forField(LargeTestModel::int)
//        println(extracted)
//    }
}