package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.engine.local.EngineReliabilitySettings
import com.lightningkite.services.data.DataSize
import com.lightningkite.services.data.DataSize.Companion.bytes
import com.lightningkite.services.data.DataSize.Companion.mebibytes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Configuration settings for the Netty HTTP server engine.
 *
 * Netty is a high-performance asynchronous event-driven network application framework that
 * supports native transports (epoll on Linux, kqueue on macOS/BSD) for optimal performance.
 * This engine supports both HTTP and WebSocket connections.
 *
 * @property host The host address to bind to (defaults to "0.0.0.0" for all interfaces)
 * @property port The port number to listen on (defaults to 8080)
 * @property realIpHeader Optional header name to extract the real client IP from (useful behind proxies/load balancers)
 * @property requestIdHeader Optional header name carrying a request ID stamped by a **trusted**
 *   reverse proxy, adopted as the authoritative request ID. Leave null (the default) to always
 *   generate one; a client-supplied ID is never trusted. Set to "X-Request-ID" behind Envoy so the
 *   proxy's capture and the server's logs share an identifier.
 * @property workerThreads Number of worker threads for handling requests. If null or 0, Netty chooses automatically (typically 2x CPU cores)
 * @property maxAggregatedContentLength Maximum size of aggregated HTTP content (request body). Defaults to 16 MiB. Limited to Int.MAX_VALUE (~2 GiB)
 * @property webSocketCompression Whether to enable WebSocket per-message deflate compression (defaults to false)
 * @property backlog The maximum number of pending connections in the accept queue (defaults to 4096 bytes, used as int)
 * @property recvBufBytes Optional TCP receive buffer size. If null, uses system default
 * @property sendBufBytes Optional TCP send buffer size. If null, uses system default
 * @property autoRead Whether to automatically read data from the channel (defaults to true). Set to false for manual flow control
 * @property reliability Shared engine reliability settings (request timeout, idle timeout, graceful
 *   shutdown drain, WebSocket backpressure). See [EngineReliabilitySettings]. Note: Netty's request
 *   body cap is governed by [maxAggregatedContentLength] (enforced by its HTTP aggregator), not by
 *   [EngineReliabilitySettings.maxBodySize]; and Netty manages its own worker pool via [workerThreads],
 *   so [EngineReliabilitySettings.workerThreads] is ignored here.
 */
@Serializable
public data class NettyRuntimeSettings(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val realIpHeader: String? = null,
    val requestIdHeader: String? = null,
    val workerThreads: Int? = null,
    val maxAggregatedContentLength: DataSize = 16.mebibytes,
    val webSocketCompression: Boolean = false,
    val backlog: DataSize = 4096.bytes,
    val recvBufBytes: DataSize? = null,
    val sendBufBytes: DataSize? = null,
    val autoRead: Boolean = true,
    val reliability: EngineReliabilitySettings = EngineReliabilitySettings(),
)

/**
 * Server setting for configuring the Netty engine runtime parameters.
 */
public val nettyRunConfig: ServerSetting.Direct<NettyRuntimeSettings> = ServerSetting(
    "nettyRunConfig",
    NettyRuntimeSettings(),
    NettyRuntimeSettings.serializer()
)

/*
 * TODO: API Recommendations
 *
 * 1. Consider validating workerThreads is positive when non-null
 * 2. Consider adding separate settings for boss thread count (currently hardcoded to 1)
 * 3. The backlog parameter uses DataSize but is converted to int - consider using Int directly for clarity
 * 4. Consider adding documentation about when to adjust recvBufBytes/sendBufBytes for performance tuning
 * 5. Consider adding idle timeout configuration (currently hardcoded to 120 seconds in NettyEngine)
 */

