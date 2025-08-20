package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.services.MetricSink

public val secretBasis: ServerSetting.Direct<SecretBasis> =
    ServerSetting("secretBasis", SecretBasis(), SecretBasis.serializer())

public val generalSettings: ServerSetting.Direct<GeneralServerSettings> =
    ServerSetting("general", GeneralServerSettings(), GeneralServerSettings.serializer())

public val metricsSettings: ServerSetting<MetricSink.Settings, MetricSink> =
    ServerSetting("metrics", MetricSink.Settings(), MetricSink.Settings.serializer())