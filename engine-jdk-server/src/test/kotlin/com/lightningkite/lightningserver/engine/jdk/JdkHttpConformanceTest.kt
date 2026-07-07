package com.lightningkite.lightningserver.engine.jdk

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
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the shared cross-engine HTTP conformance suite against the JDK HttpServer engine.
 * See [EngineHttpConformanceSuite] for the behaviors asserted.
 */
class JdkHttpConformanceTest : EngineHttpConformanceSuite() {
    override fun startEngine(port: Int, maxBodySize: Long): RunningEngine {
        val engine = JdkEngine(conformanceDefinition())
        engine.settings.run {
            generalSettings.useDefault()
            secretBasis.useDefault()
            loggingSettings.useDefault()
            telemetrySettings.useDefault()
            enginePubSub.useDefault()
            engineCache.useDefault()
            forceWebSocketPubSub.useDefault()
            applyConformanceAppDefaults()
            jdkRunConfig set JdkRuntimeSettings(
                host = "127.0.0.1",
                port = port,
                reliability = EngineReliabilitySettings(
                    maxBodySize = maxBodySize.bytes,
                    workerThreads = 4,
                    shutdownDrainTimeout = 1.seconds, // keep close() fast
                ),
            )
        }
        engine.start() // non-blocking: binds and returns
        awaitBound(port)
        return object : RunningEngine {
            override val port: Int = port
            override fun close() { engine.shutdown() }
        }
    }
}
