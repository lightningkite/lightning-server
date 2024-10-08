package com.lightningkite.lightningdb.test

import com.lightningkite.prepareModelsServerCore
import com.lightningkite.lightningdb.*
import com.lightningkite.prepareModelsShared
import com.lightningkite.serialization.*
import org.junit.Assert.*
import org.junit.Test

class MaskTest {
    init {
        prepareModelsShared()
        prepareModelsServerCore()
        prepareModelsServerTesting()
    }

    @Test fun mask() {
        val model = LargeTestModel(
            byte = 1,
            short = 1,
            int = 1,
            long = 1,
            float = 1f
        )
        val mask = mask<LargeTestModel> {
            it.byte.mask(0, it.never)
            it.short.mask(0, it.always)
            it.int.mask(0, it.byte eq 1)
            it.long.mask(0, it.byte neq 1)
            it.float.mask(0f, it.string eq "test")
        }
        mask(model).also(::println)
        mask(
            partialOf<LargeTestModel>{
                it.byte.assign(1)
                it.byte assign 1.toByte()
                it.short assign 1.toShort()
                it.int assign 1
                it.long assign 1.toLong()
                it.float assign 1f
            }
        ).also(::println)
    }

    @Test
    fun modification() {
        val matchingMod = modification<LargeTestModel> { it.embedded.value1 assign "" }
        val notMatchingModA = modification<LargeTestModel> { it.embedded.value2 assign 2 }
        val notMatchingModB = modification<LargeTestModel> { it.int assign 2 }

        val mask = updateRestrictions<LargeTestModel> { it.embedded.value1 requires it.never }

        assertTrue(mask(matchingMod) is Condition.Never)
        assertTrue(mask(notMatchingModA) is Condition.Always)
        assertTrue(mask(notMatchingModB) is Condition.Always)
    }

    @Test
    fun complexModification() {
        val mask = updateRestrictions<LargeTestModel> {
            it.embedded.value2.requires<Int>(
                requires = it.always,
                valueMust = { it gt 4 }
            )
        }

        assertTrue(mask(modification { it.embedded.value2 assign 5 }) is Condition.Always)
        assertTrue(mask(modification { it.embedded.value2 assign 4 }) is Condition.Never)
        assertTrue(mask(modification { it.embedded.value2 += 4 }) is Condition.Never)
    }

    @Test
    fun modificationList() {
//        val matchingMod = modification<LargeTestModel> { it.listEmbedded.map { it.value1 assign "" } }
//        val matchingBMod = modification<LargeTestModel> {
//            it.listEmbedded.mapIf(condition = { it.value2 eq 2 },
//                modification = { it.value1 assign "" })
//        }
//        val notMatchingModA = modification<LargeTestModel> { it.embedded.value2 assign 2 }
//        val notMatchingModB = modification<LargeTestModel> { it.int assign 2 }
//
//        val mask = updateRestrictions<LargeTestModel> {
//            it.listEmbedded.any.value1.cannotBeModified()
//        }
//
//        assertTrue(mask(matchingMod) is Condition.Never)
//        assertTrue(mask(matchingBMod) is Condition.Never)
//        assertTrue(mask(notMatchingModA) is Condition.Always)
//        assertTrue(mask(notMatchingModB) is Condition.Always)
    }

    @Test
    fun sort() {
        val matchingSort = SortPart(path<LargeTestModel>().int)
        val notMatchingSortA = SortPart(path<LargeTestModel>().byte)
        val notMatchingSortV = SortPart(path<LargeTestModel>().string)

        val mask = mask<LargeTestModel> { always(it.int.maskedTo(2)) }

        assertTrue(mask.permitSort(listOf(matchingSort)) is Condition.Never)
        assertTrue(mask.permitSort(listOf(notMatchingSortA)) is Condition.Always)
        assertTrue(mask.permitSort(listOf(notMatchingSortV)) is Condition.Always)
        assertEquals(2, mask(LargeTestModel(int = 4)).int)
    }

    @Test
    fun condition() {
        val matchingSort = condition<LargeTestModel> { it.int eq 2 }
        val notMatchingSortA = condition<LargeTestModel> { it.byte eq 2 }
        val notMatchingSortV = condition<LargeTestModel> { it.string eq "" }

        val mask = mask<LargeTestModel> { always(it.int.maskedTo(2)) }

        assertTrue(mask(matchingSort) is Condition.Never)
        assertTrue(mask(notMatchingSortA) is Condition.Always)
        assertTrue(mask(notMatchingSortV) is Condition.Always)
    }
}