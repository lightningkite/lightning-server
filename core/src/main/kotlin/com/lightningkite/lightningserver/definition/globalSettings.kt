package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.services.LoggingSettings
import com.lightningkite.services.OpenTelemetry
import com.lightningkite.services.otel.OpenTelemetrySettings
import kotlinx.serialization.builtins.nullable

public val secretBasis: ServerSetting.Direct<SecretBasis> =
    ServerSetting("secretBasis", SecretBasis(), SecretBasis.serializer())

public val generalSettings: ServerSetting.Direct<GeneralServerSettings> =
    ServerSetting("general", GeneralServerSettings(), GeneralServerSettings.serializer())

public val telemetrySettings: ServerSetting<OpenTelemetrySettings?, OpenTelemetry?> =
    ServerSetting("telemetry", null, OpenTelemetrySettings.serializer().nullable) { it?.invoke("telemetry", this) }

public val loggingSettings: ServerSetting.Direct<LoggingSettings> =
    ServerSetting("logging", LoggingSettings(), LoggingSettings.serializer())
