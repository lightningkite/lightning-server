package com.lightningkite.lightningserver.engine.ktor

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
import io.ktor.server.cio.CIO as ServerCIO
import kotlin.concurrent.thread

/**
 * Runs the shared cross-engine HTTP conformance suite against the Ktor (CIO) engine.
 * See [EngineHttpConformanceSuite] for the behaviors asserted.
 */
class KtorHttpConformanceTest : EngineHttpConformanceSuite() {
    override fun startEngine(port: Int, maxBodySize: Long): RunningEngine {
        val engine = KtorEngine(conformanceDefinition())
        engine.settings.run {
            generalSettings.useDefault()
            secretBasis.useDefault()
            loggingSettings.useDefault()
            telemetrySettings.useDefault()
            enginePubSub.useDefault()
            engineCache.useDefault()
            forceWebSocketPubSub.useDefault()
            applyConformanceAppDefaults()
            ktorRunConfig set KtorRuntimeSettings(
                host = "127.0.0.1",
                port = port,
                reliability = EngineReliabilitySettings(maxBodySize = maxBodySize.bytes),
            )
        }
        // KtorEngine.start(factory) blocks (wait = true), so it runs on a daemon thread; interrupting it
        // on close mirrors the existing KtorReliabilityTest teardown.
        val serverThread = thread(start = true, isDaemon = true) { engine.start(ServerCIO) }
        awaitBound(port)
        return object : RunningEngine {
            override val port: Int = port
            override fun close() { serverThread.interrupt() }
        }
    }
}
