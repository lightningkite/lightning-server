package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.divideErrorTest
import com.lightningkite.lightningserver.guide.samples.divideSuccessTest
import com.lightningkite.lightningserver.guide.samples.successCodeTest
import kotlin.test.Test

class TypedEndpointsTest {
    @Test fun `divide returns correct quotient`() { divideSuccessTest() }
    @Test fun `divide throws HttpStatusException with correct detail on division by zero`() { divideErrorTest() }
    @Test fun `endpoint with Created success code returns typed output`() { successCodeTest() }
}
