package com.lightningkite.lightningserver.engine.awsserverless

import com.lightningkite.lightningserver.definition.CorsSettings
import com.lightningkite.lightningserver.definition.ServerSetting
import kotlinx.serialization.Serializable


@Serializable
public data class AwsLambdaRuntimeSettings(
    val cors: CorsSettings = CorsSettings(),
)

public val awsLambdaRuntimeSettings: ServerSetting.Direct<AwsLambdaRuntimeSettings> = ServerSetting(
    "awsLambdaRuntimeSettings",
    AwsLambdaRuntimeSettings(),
    AwsLambdaRuntimeSettings.serializer()
)