package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.*
import org.junit.Test

class OverviewTest {
    @Test
    fun `test settings override the declared default`() {
        overviewServerTest()
    }

    @Test
    fun `a context(Engine) function reads settings from a test block`() {
        overviewContextTest()
    }
}
