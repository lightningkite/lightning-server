package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.*
import org.junit.Test

// Exercises every sample function from FirstEndpointSamples.kt.
// Each function contains its own assertions; failures here mean a guide
// example is broken, not just untested.
class FirstEndpointTest {

    @Test
    fun `hello server returns greeting`() {
        helloServerTest()
    }

    @Test
    fun `greet server interpolates path argument`() {
        greetServerTest()
    }

    @Test
    fun `typed echo endpoint returns structured output`() {
        echoServerTest()
    }
}
