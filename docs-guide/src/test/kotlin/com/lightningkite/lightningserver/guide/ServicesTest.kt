package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.counterTest
import kotlin.test.Test

class ServicesTest {
    @Test fun `cache-backed counter increments and reads correctly`() { counterTest() }
}
