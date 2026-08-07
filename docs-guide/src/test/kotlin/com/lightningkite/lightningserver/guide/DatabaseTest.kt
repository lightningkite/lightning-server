package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.databaseTest
import kotlin.test.Test

class DatabaseTest {
    @Test fun `database operations insert find update delete work correctly`() { databaseTest() }
}
