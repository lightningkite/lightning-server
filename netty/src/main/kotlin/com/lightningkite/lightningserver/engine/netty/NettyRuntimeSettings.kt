package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.lightningserver.definition.ServerSetting
import kotlinx.serialization.Serializable

@Serializable
public data class NettyRuntimeSettings(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val realIpHeader: String? = null,
)

public val nettyRunConfig: ServerSetting.Direct<NettyRuntimeSettings> = ServerSetting(
    "nettyRunConfig",
    NettyRuntimeSettings(),
    NettyRuntimeSettings.serializer()
)

