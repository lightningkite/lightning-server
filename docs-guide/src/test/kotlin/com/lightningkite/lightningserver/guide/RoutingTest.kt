package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.httpMethodsTest
import com.lightningkite.lightningserver.guide.samples.multiArgTest
import com.lightningkite.lightningserver.guide.samples.nestedPathsTest
import com.lightningkite.lightningserver.guide.samples.queryParamsTest
import com.lightningkite.lightningserver.guide.samples.subBuilderTest
import kotlin.test.Test

class RoutingTest {
    @Test fun `nested paths respond correctly`() { nestedPathsTest() }
    @Test fun `put patch delete respond correctly`() { httpMethodsTest() }
    @Test fun `two typed path arguments resolve correctly`() { multiArgTest() }
    @Test fun `sub-builder mounted with include is reachable`() { subBuilderTest() }
    @Test fun `query parameters are parsed and read correctly`() { queryParamsTest() }
}
