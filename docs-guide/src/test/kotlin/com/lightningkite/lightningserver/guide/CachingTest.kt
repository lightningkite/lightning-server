package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.*
import kotlin.test.Test

class CachingTest {
    @Test fun `cache-aside endpoint populates the cache`() { forecastTest() }
    @Test fun `cache operations behave as documented`() { cacheOperationsTest() }
    @Test fun `typed cache keys`() { cacheKeyTest() }
    @Test fun `ttl expiry follows the coroutine clock`() { cacheTtlTest() }
}
