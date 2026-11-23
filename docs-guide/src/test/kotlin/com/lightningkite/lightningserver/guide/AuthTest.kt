package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.authRejectionTest
import com.lightningkite.lightningserver.guide.samples.authRequirementTest
import com.lightningkite.lightningserver.guide.samples.authTest
import kotlin.test.Test

class AuthTest {
    @Test
    fun `authenticated profile endpoint accepts valid token and returns user`() { authTest() }

    @Test
    fun `protected endpoint rejects unauthenticated request with 403`() { authRejectionTest() }

    @Test
    fun `scope, maxAge, custom requirements and auth cache keys behave as documented`() { authRequirementTest() }
}
