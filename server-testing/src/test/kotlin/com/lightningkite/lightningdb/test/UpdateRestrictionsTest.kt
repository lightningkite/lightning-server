package com.lightningkite.lightningdb.test

import com.lightningkite.lightningdb.*
import org.junit.Test
import kotlin.test.assertEquals

class UpdateRestrictionsTest {
    @Test fun test() {
        val restrictions = updateRestrictions<LargeTestModel> {
            it.embedded.cannotBeModified()
        }
        assertEquals(Condition.Never<LargeTestModel>(), restrictions(modification { it.embedded assign ClassUsedForEmbedding() }))
        assertEquals(Condition.Never<LargeTestModel>(), restrictions(modification { it.embedded.value2 assign 2 }))
        assertEquals(Condition.Always<LargeTestModel>(), restrictions(modification { it.int assign 2 }))
    }
}