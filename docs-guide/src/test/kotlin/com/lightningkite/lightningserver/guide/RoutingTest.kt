package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.*
import kotlin.test.Test

class RoutingTest {
    @Test fun `minimal server answers both routes`() { routingMinimalTest() }
    @Test fun `put patch delete share one path`() { routingMethodsTest() }
    @Test fun `two typed path arguments resolve`() { routingMultiArgTest() }
    @Test fun `trailing segments are captured`() { routingTrailingTest() }
    @Test fun `query parameters headers and body are read`() { routingRequestTest() }
    @Test fun `responses carry status and headers`() { routingResponseTest() }
    @Test fun `included builder is reachable`() { routingIncludeTest() }
}
