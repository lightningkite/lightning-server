package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.counterTest
import com.lightningkite.lightningserver.guide.samples.sharedCacheTest
import kotlin.test.Test

class ServicesTest {
    @Test fun `cache-backed counter increments`() { counterTest() }
    @Test fun `setStatic injects an instance`() { com.lightningkite.lightningserver.guide.samples.counterStaticTest() }
    @Test fun `bound engine cache is the app cache`() { sharedCacheTest() }
}
