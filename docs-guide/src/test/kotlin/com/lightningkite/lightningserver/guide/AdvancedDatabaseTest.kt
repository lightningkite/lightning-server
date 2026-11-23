package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.advancedDatabaseTest
import kotlin.test.Test

class AdvancedDatabaseTest {
    @Test fun `sorting paging aggregates text search bulk writes and signals`() { advancedDatabaseTest() }
}
