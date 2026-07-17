package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.cacheTest
import kotlin.test.Test

class CachingTest {
    @Test
    fun `cache get, set, remove, getAndRemove, setIfNotExists, and add are correct`() { cacheTest() }
}
