package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.oauthStartTest
import kotlin.test.Test

class OauthTest {
    @Test
    fun `start returns the provider authorization URL with PKCE`() { oauthStartTest() }
}
