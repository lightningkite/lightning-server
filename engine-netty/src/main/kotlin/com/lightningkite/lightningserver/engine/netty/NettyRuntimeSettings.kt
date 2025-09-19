package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.DataSize
import com.lightningkite.DataSize.Companion.bytes
import com.lightningkite.DataSize.Companion.mebibytes
import com.lightningkite.lightningserver.definition.ServerSetting
import kotlinx.serialization.Serializable

@Serializable
public data class NettyRuntimeSettings(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val realIpHeader: String? = null,
    val workerThreads: Int? = null,
    val maxAggregatedContentLength: DataSize = 16.mebibytes,
    val websocketCompression: Boolean = false,
    val backlog: DataSize = 4096.bytes,
    val recvBufBytes: DataSize? = null,
    val sendBufBytes: DataSize? = null,
    val autoRead: Boolean = true,
)

public val nettyRunConfig: ServerSetting.Direct<NettyRuntimeSettings> = ServerSetting(
    "nettyRunConfig",
    NettyRuntimeSettings(),
    NettyRuntimeSettings.serializer()
)

