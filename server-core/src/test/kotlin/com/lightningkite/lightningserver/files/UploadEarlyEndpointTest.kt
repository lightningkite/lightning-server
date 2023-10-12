package com.lightningkite.lightningserver.files

import com.lightningkite.default
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.now
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Assert.*
import java.util.UUID
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class UploadEarlyEndpointTest {
    @Test fun testSigning() {
        var now = now()
        Clock.default = object: Clock {
            override fun now(): Instant = now
        }
        repeat(1000) {
            now.plus(1.milliseconds)
            assertTrue(TestSettings.earlyUpload.verifyUrl(TestSettings.earlyUpload.signUrl("https://test.com/test.jpg")))
        }
        Clock.default = Clock.System
    }
}