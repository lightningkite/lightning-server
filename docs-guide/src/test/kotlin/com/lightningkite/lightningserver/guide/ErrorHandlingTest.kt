package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.errorHttpTest
import com.lightningkite.lightningserver.guide.samples.errorTypedTest
import kotlin.test.Test

class ErrorHandlingTest {
    @Test
    fun `typed test propagates HttpStatusException with correct status code and detail`() { errorTypedTest() }

    @Test
    fun `HTTP pipeline test returns HttpResponse with correct status code`() { errorHttpTest() }
}
