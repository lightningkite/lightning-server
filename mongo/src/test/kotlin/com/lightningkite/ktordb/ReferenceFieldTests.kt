package com.lightningkite.ktordb

import com.lightningkite.ktordb.application.*
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReferenceFieldTests {


//    @Test
//    fun testReferenceFields() {
//        val condition = LargeTestModel.chain.list eq listOf()
//        assertTrue(condition.referencesField(LargeTestModel.fields.list))
//        assertFalse(condition.referencesField(LargeTestModel.fields.int))
//
//        val modification = LargeTestModel.chain.list assign listOf()
//        assertTrue(modification.referencesField(LargeTestModel.fields.list))
//        assertFalse(modification.referencesField(LargeTestModel.fields.int))
//    }
//
//    @Test
//    fun extraction() {
//        val condition = (LargeTestModel.chain.string eq "asdf") and (LargeTestModel.chain.int inside listOf(
//            1,
//            2,
//            3
//        ) or (LargeTestModel.chain.int eq 4))
//        val extracted = condition.forField(LargeTestModelFields.int)
//        println(extracted)
//    }
}