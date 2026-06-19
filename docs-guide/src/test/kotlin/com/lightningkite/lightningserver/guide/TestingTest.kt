package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.*
import kotlin.test.Test

class TestingTest {
    @Test fun `plain handler test passes`() { plainHandlerTest() }
    @Test fun `noAuth typed endpoint returns output directly`() { noAuthTypedTest() }
    @Test fun `authenticated typed endpoint with testAuth`() { authTypedTest() }
    @Test fun `error path catches HttpStatusException with correct code and detail`() { errorPathTest() }
    @Test fun `runBlocking explanation compiles and runs`() { runBlockingExplanation() }
}
