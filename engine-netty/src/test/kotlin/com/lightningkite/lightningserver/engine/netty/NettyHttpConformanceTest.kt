package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.engine.conformance.EngineHttpConformanceSuite
import com.lightningkite.lightningserver.engine.local.EngineReliabilitySettings
import com.lightningkite.lightningserver.engine.local.engineCache
import com.lightningkite.lightningserver.engine.local.enginePubSub
import com.lightningkite.lightningserver.engine.local.forceWebSocketPubSub
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.data.DataSize.Companion.bytes
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the shared cross-engine HTTP conformance suite against the Netty engine.
 * See [EngineHttpConformanceSuite] for the behaviors asserted.
 *
 * Netty caps request bodies via [NettyRuntimeSettings.maxAggregatedContentLength] (its HTTP
 * aggregator), not [EngineReliabilitySettings.maxBodySize] like the other engines, so the shared
 * [maxBodySize] is mapped onto that field here.
 */
class NettyHttpConformanceTest : EngineHttpConformanceSuite() {
    override fun startEngine(port: Int, maxBodySize: Long): RunningEngine {
        val engine = NettyEngine(conformanceDefinition())
        engine.settings.run {
            generalSettings.useDefault()
            secretBasis.useDefault()
            telemetrySettings.useDefault()
            loggingSettings.useDefault()
            enginePubSub.useDefault()
            engineCache.useDefault()
            forceWebSocketPubSub.useDefault()
            com.lightningkite.lightningserver.websockets.webSocketSettings.useDefault()
            applyConformanceAppDefaults()
            nettyRunConfig set NettyRuntimeSettings(
                host = "127.0.0.1",
                port = port,
                maxAggregatedContentLength = maxBodySize.bytes,
                // Keep per-test teardown fast; the default 25s drain otherwise leaves worker groups
                // lingering across all nine tests and drags out the run.
                reliability = EngineReliabilitySettings(shutdownDrainTimeout = 1.seconds),
            )
        }
        // NettyEngine.start() blocks on the server channel's closeFuture().sync(), so it runs on a
        // daemon thread; shutdown() completes that future and unblocks the thread.
        val serverThread = thread(start = true, isDaemon = true) { engine.start() }
        awaitBound(port)
        return object : RunningEngine {
            override val port: Int = engine.boundAddress?.port ?: port
            override fun close() {
                engine.shutdown()
                serverThread.interrupt()
            }
        }
    }
}
