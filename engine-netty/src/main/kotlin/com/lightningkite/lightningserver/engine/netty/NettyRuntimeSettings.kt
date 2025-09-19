package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.lightningserver.definition.ServerSetting
import kotlinx.serialization.Serializable

@Serializable
public data class NettyRuntimeSettings(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val realIpHeader: String? = null,
    val workerThreads: Int? = null,
    val maxAggregatedContentLengthBytes: Int = 16 * 1024 * 1024,
    val websocketCompression: Boolean = false,
    val backlog: Int = 4096,
    val recvBufBytes: Int? = null,
    val sendBufBytes: Int? = null,
    val autoRead: Boolean = true,
)

public val nettyRunConfig: ServerSetting.Direct<NettyRuntimeSettings> = ServerSetting(
    "nettyRunConfig",
    NettyRuntimeSettings(),
    NettyRuntimeSettings.serializer()
)

