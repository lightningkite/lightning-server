package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.condensed.*
import kotlin.test.Test

class CondensedGuideTest {
    @Test fun plainHandler() = plainHandlerTest()
    @Test fun typed() = typedTest()
    @Test fun auth() = authTest()
    @Test fun emailLogin() = emailLoginTest()
    @Test fun services() = servicesTest()
    @Test fun webSocket() = webSocketTest()
    @Test fun schedule() = scheduleTest()
    @Test fun corsRateLimit() = corsRateLimitTest()
    @Test fun builds() { Server.build() }
}
