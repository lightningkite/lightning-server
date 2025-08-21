package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.services.ExceptionReporter
import com.lightningkite.services.MetricReporter

public val secretBasis: ServerSetting.Direct<SecretBasis> =
    ServerSetting("secretBasis", SecretBasis(), SecretBasis.serializer())

public val generalSettings: ServerSetting.Direct<GeneralServerSettings> =
    ServerSetting("general", GeneralServerSettings(), GeneralServerSettings.serializer())

public val metricsSettings: ServerSetting<MetricReporter.Settings, MetricReporter> =
    ServerSetting("metrics", MetricReporter.Settings(), MetricReporter.Settings.serializer())

public val exceptionSettings: ServerSetting<ExceptionReporter.Settings, ExceptionReporter> =
    ServerSetting("exceptions", ExceptionReporter.Settings(), ExceptionReporter.Settings.serializer())