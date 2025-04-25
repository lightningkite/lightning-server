package com.lightningkite.lightningserver.db.test

import com.lightningkite.lightningserver.db.Condition
import com.lightningkite.lightningserver.db.Modification

object LargeTestModelModification {
    class Case(
        val modification: Modification<LargeTestModel>,
        val before: LargeTestModel,
        val after: LargeTestModel,
    )
}