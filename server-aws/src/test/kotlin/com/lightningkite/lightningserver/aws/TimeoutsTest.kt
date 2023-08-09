package com.lightningkite.lightningserver.aws

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.TimeoutException
import kotlin.test.fail

class TimeoutsTest {
    @Test
    fun testCoroutineCancel() {
        try {
            val result = blockingTimeout(100L) {
                Thread.sleep(10_000L)
                "Never should see this"
            }
            fail()
        } catch(e: TimeoutException) {
            // Success!
        }
    }
}