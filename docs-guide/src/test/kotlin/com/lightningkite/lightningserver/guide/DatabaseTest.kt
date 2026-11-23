package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.databaseDslTest
import com.lightningkite.lightningserver.guide.samples.databaseTest
import kotlin.test.Test

class DatabaseTest {
    @Test fun `crud operations against the ram database`() { databaseTest() }
    @Test fun `condition and modification examples evaluate as documented`() { databaseDslTest() }
}
