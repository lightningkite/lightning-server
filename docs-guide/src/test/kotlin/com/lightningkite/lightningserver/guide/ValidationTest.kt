package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.validationPassTest
import com.lightningkite.lightningserver.guide.samples.validationRejectTest
import com.lightningkite.lightningserver.guide.samples.validationRejectTypedTest
import kotlin.test.Test

class ValidationTest {
    @Test fun `typed test helper rejects input violating MaxLength`() { validationRejectTypedTest() }
    @Test fun `validation rejects input violating MaxLength with HTTP 400`() { validationRejectTest() }
    @Test fun `validation passes valid input through to implementation with HTTP 201`() { validationPassTest() }
}
