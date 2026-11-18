package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.*
import kotlin.test.Test

class BulkEndpointsTest {
    @Test fun `bulk returns a result or an error per part`() { bulkTest() }
    @Test fun `funnel instance can be started, stepped and completed`() { funnelTest() }
}
