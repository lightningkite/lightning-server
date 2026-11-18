package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.*
import kotlin.test.Test

class WebSocketsTest {
    @Test fun `echo socket replies to each frame`() { wsEchoTest() }
    @Test fun `room topics deliver only to subscribers of that room`() { wsChatTest() }
    @Test fun `typed socket decodes input and encodes output`() { wsTypedTest() }
}
