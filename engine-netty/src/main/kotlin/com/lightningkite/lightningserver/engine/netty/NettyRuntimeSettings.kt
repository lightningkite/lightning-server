package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.engine.local.EngineReliabilitySettings
import com.lightningkite.services.data.DataSize
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
 *   generate one; a client-supplied ID is never trusted. The proxy must stamp version 7 UUIDs
 *   (Envoy's built-in X-Request-ID is version 4); see [requestIdentity].
 * @property workerThreads Number of I/O event-loop threads. If null or 0, Netty chooses automatically (typically 2x CPU cores). Handlers do not run on these threads
 * @property webSocketCompression Whether to enable WebSocket per-message deflate compression (defaults to false)
 * @property backlog The maximum number of pending connections in the accept queue (`SO_BACKLOG`; defaults to 4096)
 * @property recvBufBytes Optional TCP receive buffer size. If null, uses system default
 * @property sendBufBytes Optional TCP send buffer size. If null, uses system default
 * @property reliability Shared engine reliability settings (max request body size, idle timeout,
 *   graceful shutdown drain, WebSocket backpressure). See [EngineReliabilitySettings]. Handlers run on
 *   `Dispatchers.Default`, so [EngineReliabilitySettings.workerThreads] is ignored here; [workerThreads]
 *   sizes the I/O event loops only.
 */
@Serializable
public data class NettyRuntimeSettings(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val realIpHeader: String? = null,
    val requestIdHeader: String? = null,
    val workerThreads: Int? = null,
    val webSocketCompression: Boolean = false,
    val backlog: Int = 4096,
    val recvBufBytes: DataSize? = null,
    val sendBufBytes: DataSize? = null,
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
