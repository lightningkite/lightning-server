package com.lightningkite.lightningserver.definition

import com.lightningkite.services.MetricSink

public val secretBasis: ServerSetting.Direct<SecretBasis> =
    ServerSetting("secretBasis", SecretBasis.serializer(), SecretBasis())

public val generalSettings: ServerSetting.Direct<GeneralServerSettings> =
    ServerSetting("generalSettings", GeneralServerSettings.serializer(), GeneralServerSettings())

public val metricsSettings: ServerSetting<MetricSink.Settings, MetricSink> =
    ServerSetting("metrics", MetricSink.Settings.serializer(), MetricSink.Settings())