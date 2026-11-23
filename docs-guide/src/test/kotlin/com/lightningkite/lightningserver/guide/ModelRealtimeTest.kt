package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.ticketUpdatesTest
import kotlin.test.Test

class ModelRealtimeTest {
    @Test fun `websocket streams permitted changes for the subscribed condition`() { ticketUpdatesTest() }
}
