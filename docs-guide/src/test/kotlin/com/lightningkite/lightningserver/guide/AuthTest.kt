package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.authTest
import kotlin.test.Test

class AuthTest {
    @Test
    fun `authenticated profile endpoint accepts valid token and returns user`() { authTest() }
}
