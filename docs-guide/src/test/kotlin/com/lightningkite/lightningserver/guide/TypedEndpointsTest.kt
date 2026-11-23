package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.*
import kotlin.test.Test

class TypedEndpointsTest {
    @Test fun `divide returns the quotient`() { typedMinimalTest() }
    @Test fun `query input, path arguments, success code and internal calls work`() { typedCatalogTest() }
    @Test fun `errors surface as exceptions in typed tests and statuses over HTTP`() { typedErrorTest() }
}
