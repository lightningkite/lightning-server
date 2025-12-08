package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.services.LoggingSettings
import com.lightningkite.services.OpenTelemetry
import com.lightningkite.services.otel.OpenTelemetrySettings
import kotlinx.serialization.builtins.nullable

/**
 * Global setting for secret encryption basis.
 *
 * The [SecretBasis] is used for encrypting and decrypting sensitive data throughout the server,
 * such as session tokens, API keys, and other secrets. This setting is critical for security
 * and should be configured with a strong, randomly generated secret in production environments.
 *
 * Warning: Changing the secret basis will invalidate all existing encrypted data, including
 * active user sessions. Only change this value during initial setup or in controlled migration scenarios.
 */
public val secretBasis: ServerSetting.Direct<SecretBasis> =
    ServerSetting("secretBasis", SecretBasis(), SecretBasis.serializer())

/**
 * Global setting for general server configuration.
 *
 * Provides access to [GeneralServerSettings], which includes project name, public/WebSocket URLs,
 * debug mode, and other runtime configuration values used throughout the server.
 *
 * @see GeneralServerSettings
 */
public val generalSettings: ServerSetting.Direct<GeneralServerSettings> =
    ServerSetting("general", GeneralServerSettings(), GeneralServerSettings.serializer())

/**
 * Global setting for OpenTelemetry configuration.
 *
 * When configured, enables distributed tracing and metrics collection via OpenTelemetry.
 * If null (the default), telemetry is disabled. Configure this to send traces to services
 * like Jaeger, Zipkin, or cloud observability platforms.
 *
 * @see OpenTelemetrySettings
 */
public val telemetrySettings: ServerSetting<OpenTelemetrySettings?, OpenTelemetry?> =
    ServerSetting("telemetry", null, OpenTelemetrySettings.serializer().nullable) { it?.invoke("telemetry", this) }

/**
 * Global setting for logging configuration.
 *
 * Controls the logging behavior of the server, including log levels, output formats,
 * and destinations. Uses the standard [LoggingSettings] from the services library.
 *
 * @see LoggingSettings
 */
public val loggingSettings: ServerSetting.Direct<LoggingSettings> =
    ServerSetting("logging", LoggingSettings(), LoggingSettings.serializer())
