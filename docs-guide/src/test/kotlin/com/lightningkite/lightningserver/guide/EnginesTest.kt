package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.engine.ktor.KtorRuntimeSettings
import com.lightningkite.lightningserver.engine.local.WsOversizePolicy
import com.lightningkite.lightningserver.engine.netty.NettyRuntimeSettings
import com.lightningkite.lightningserver.guide.samples.SlowReportServer
import com.lightningkite.services.data.DataSize.Companion.mebibytes
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// The JSON here is copied from engines.md, so the documented value formats (bytes, ISO-8601 durations) stay true.
class EnginesTest {
    @Test
    fun `documented ktor run config parses`() {
        val parsed = Json.decodeFromString(
            KtorRuntimeSettings.serializer(), """
            {
                "host": "0.0.0.0",
                "port": 8080,
                "realIpHeader": "X-Forwarded-For",
                "reliability": {
                    "maxBodySize": 67108864,
                    "shutdownDrainTimeout": "PT20S",
                    "webSocketOversizePolicy": "SUSPEND",
                    "scheduleLockTtl": "PT4H"
                }
            }
            """
        )
        assertEquals(64.mebibytes, parsed.reliability.maxBodySize)
        assertEquals(20.seconds, parsed.reliability.shutdownDrainTimeout)
        assertEquals(WsOversizePolicy.SUSPEND, parsed.reliability.webSocketOversizePolicy)
        assertEquals(4.hours, parsed.reliability.scheduleLockTtl)
    }

    @Test
    fun `documented netty run config parses`() {
        val parsed = Json.decodeFromString(
            NettyRuntimeSettings.serializer(), """
            {
                "port": 8080,
                "workerThreads": 8,
                "maxAggregatedContentLength": 33554432,
                "webSocketCompression": true,
                "reliability": {
                    "idleTimeout": "PT1M",
                    "shutdownDrainTimeout": "PT20S"
                }
            }
            """
        )
        assertEquals(32.mebibytes, parsed.maxAggregatedContentLength)
        assertEquals(1.minutes, parsed.reliability.idleTimeout)
    }

    @Test
    fun `handler timeout is per handler`() {
        assertEquals(5.minutes, SlowReportServer.report.timeout)
    }
}
