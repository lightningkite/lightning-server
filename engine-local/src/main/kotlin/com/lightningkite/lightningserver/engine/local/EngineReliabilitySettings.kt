package com.lightningkite.lightningserver.engine.local

import com.lightningkite.services.data.DataSize
import com.lightningkite.services.data.DataSize.Companion.mebibytes
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Policy applied when a WebSocket peer sends inbound frames faster than the handler consumes them
 * and the bounded inbound buffer ([EngineReliabilitySettings.webSocketInboundBuffer]) overflows.
 */
public enum class WsOversizePolicy {
    /** Close the socket with WebSocket close code 1009 (message too big). This is the safe default. */
    CLOSE,

    /** Drop the oldest buffered frame to make room for the new one. Lossy, but keeps the socket open. */
    DROP_OLDEST,

    /** Suspend the reader until the handler drains a slot. Applies true backpressure to the peer. */
    SUSPEND,
}

/**
 * Cross-engine reliability and resource-protection settings shared by all [LocalEngine] subclasses
 * (Ktor, Netty, JDK).
 *
 * These guard rails protect a single-process server from a handful of common failure modes:
 * slow/stuck handlers, oversized request bodies, idle connections, ungraceful restarts, and
 * unbounded WebSocket buffering. Defaults are chosen to be safe for typical web APIs; tune them
 * per deployment via the engine's run-config setting.
 *
 * Note: per-request timeouts are NOT configured here. They are a per-handler concern —
 * [com.lightningkite.lightningserver.http.HttpHandler.timeout] (default 30s) — enforced centrally in
 * `ServerRuntime.handle`, so the limit is respected uniformly across every engine rather than
 * duplicated in each adapter.
 *
 * @property maxBodySize Maximum accepted request body size. Requests whose `Content-Length` exceeds
 *   this, or whose streamed body grows past it, are rejected with 413 Payload Too Large before the
 *   full body is buffered. Defaults to 16 MiB.
 * @property idleTimeout How long an idle keep-alive connection may sit with no read/write activity
 *   before it is closed. **Netty-only** — the Ktor and JDK engines do not expose a per-connection
 *   idle timeout, so this value is ignored by them. Defaults to 120 seconds.
 * @property shutdownDrainTimeout During graceful shutdown, the maximum time to wait for in-flight
 *   requests to complete before forcing termination. Defaults to 25 seconds.
 * @property webSocketInboundBuffer Capacity (in frames) of the bounded channel that buffers inbound
 *   WebSocket frames between the socket reader and the handler. Defaults to 256.
 * @property webSocketOversizePolicy What to do when [webSocketInboundBuffer] overflows. Defaults to
 *   [WsOversizePolicy.CLOSE].
 * @property workerThreads Size of the request-processing thread pool for engines that run a managed
 *   pool (currently the JDK engine). If null, the engine picks a default
 *   (`availableProcessors() * 2`). Ignored by Ktor and Netty, which manage their own event loops.
 * @property scheduleLockTtl Expiry of the distributed lock a server instance holds while running a
 *   scheduled task, preventing other instances from running the same tick concurrently. The lock is
 *   normally released as soon as the tick finishes (and on graceful shutdown), so this TTL only acts
 *   as a backstop after a hard crash. A scheduled task that runs longer than this may be started
 *   concurrently on another instance once the lock expires — keep ticks shorter than this value, or
 *   raise it. Defaults to 1 hour.
 */
@Serializable
public data class EngineReliabilitySettings(
    val maxBodySize: DataSize = 16.mebibytes,
    val idleTimeout: Duration = 120.seconds,
    val shutdownDrainTimeout: Duration = 25.seconds,
    val webSocketInboundBuffer: Int = 256,
    val webSocketOversizePolicy: WsOversizePolicy = WsOversizePolicy.CLOSE,
    val workerThreads: Int? = null,
    val scheduleLockTtl: Duration = 1.hours,
)
