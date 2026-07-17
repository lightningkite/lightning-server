package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.bulkTest
import kotlin.test.Test

class BulkEndpointsTest {
    @Test
    fun `bulk endpoint batches multiple sub-requests and returns per-sub-request results`() { bulkTest() }
}
