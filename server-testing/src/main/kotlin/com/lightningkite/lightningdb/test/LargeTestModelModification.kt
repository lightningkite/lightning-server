package com.lightningkite.lightningdb.test

import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.Modification

object LargeTestModelModification {
    class Case(
        val modification: Modification<LargeTestModel>,
        val before: LargeTestModel,
        val after: LargeTestModel,
    )
}