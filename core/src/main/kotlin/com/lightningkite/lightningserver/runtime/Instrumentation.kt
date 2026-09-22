package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.services.data.Unsafe
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryTrace
import com.lightningkite.services.telemetry.emptyTelemetryAttributes
import com.lightningkite.services.telemetry.telemetryTrace

/**
 * Instruments a suspend block with the metrics backend, creating a named child span.
 *
 * All [attributes] are attached to the span at start. If telemetry is not configured the
 * call is a transparent no-op. Errors are recorded and re-thrown automatically by the backend.
 *
 * @param name Short operation name (e.g. "handler", "willConnect")
 * @param attributes Initial attributes attached to the span
 * @param action The code to run inside the span
 * @return The result of [action]
 */
context(runtime: Engine)
public suspend inline fun <T> instrument(
    name: String,
    attributes: TelemetryAttributes = emptyTelemetryAttributes(),
    crossinline action: suspend () -> T,
): T = runtime.telemetryTrace(name, attributes) { action() }

internal suspend fun <T> ServerRuntime.interceptExecution(body: suspend context(ServerRuntime) () -> T): T =
    server.compiledExecutionInterceptors.intercept(body)

@InternalLightningServerApi
public suspend inline fun <T> Engine.execute(
    opName: String,
    scope: Execution,
    attributes: TelemetryAttributes = emptyTelemetryAttributes(),
    crossinline action: suspend ServerRuntime.(trace: TelemetryTrace) -> T
): T {
    @OptIn(Unsafe::class)
    // SAFETY: The created runtime is immediately used and wrapped in the execution interceptors
    return with(createRuntime(scope)) {
        telemetryTrace(opName, attributes) { trace ->
            server.compiledExecutionInterceptors.intercept { serverRuntime.action(trace) }
        }
    }
}

@InternalLightningServerApi
public suspend inline fun <T> Engine.executeWithoutTelemetry(
    scope: Execution,
    crossinline action: suspend ServerRuntime.() -> T
): T {
    @OptIn(Unsafe::class)
    // SAFETY: The created runtime is immediately used and wrapped in the execution interceptors
    return with(createRuntime(scope)) {
        server.compiledExecutionInterceptors.intercept { serverRuntime.action() }
    }
}

/*
 * TODO: API Recommendations
 *
 * 1. The telemetry span names use different formats: "http.route" vs "WEBSOCKET.WILLCONNECT".
 *    Standardize naming conventions for consistency.
 */
