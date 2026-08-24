package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.broadcastWsTest
import com.lightningkite.lightningserver.guide.samples.echoWsTest
import kotlin.test.Test

class WebSocketsTest {
    @Test
    fun `echo webSocket reflects frames back to client`() { echoWsTest() }

    @Test
    fun `broadcast topic pushes to all subscribed connections`() { broadcastWsTest() }
}
