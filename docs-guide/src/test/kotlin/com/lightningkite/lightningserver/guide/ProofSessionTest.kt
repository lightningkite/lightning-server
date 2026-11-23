package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.adminNeedsTwoFactorsTest
import com.lightningkite.lightningserver.guide.samples.emailLoginTest
import com.lightningkite.lightningserver.guide.samples.passwordLoginTest
import com.lightningkite.lightningserver.guide.samples.screenedEmailTest
import kotlin.test.Test

class ProofSessionTest {
    @Test
    fun `email PIN login yields a working access token`() { emailLoginTest() }

    @Test
    fun `password proof logs in once and enforces evaluatePassword`() { passwordLoginTest() }

    @Test
    fun `admins need two factors`() { adminNeedsTwoFactorsTest() }

    @Test
    fun `screenEmail rejects, suppresses, and allows`() { screenedEmailTest() }
}
