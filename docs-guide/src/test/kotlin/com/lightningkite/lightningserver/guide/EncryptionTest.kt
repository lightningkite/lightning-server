package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.encryptionTest
import kotlin.test.Test

class EncryptionTest {
    @Test
    fun `cipher round-trips and detects tampering, signer binds data and expiry, hashes verify`() { encryptionTest() }
}
