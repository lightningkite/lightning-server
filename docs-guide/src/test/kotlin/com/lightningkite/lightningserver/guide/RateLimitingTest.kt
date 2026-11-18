package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.*
import kotlin.test.Test

class RateLimitingTest {
    @Test
    fun `a caller over budget gets a 429 with Retry-After`() { rateLimitTest() }

    @Test
    fun `anonymous callers are keyed by IP`() { perUserRateLimitTest() }

    @Test
    fun `a null setting turns limiting off`() { rateLimitOffTest() }
}
