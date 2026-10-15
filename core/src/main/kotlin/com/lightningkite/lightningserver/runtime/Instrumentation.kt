package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.services.data.Unsafe
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryTrace
import com.lightningkite.services.telemetry.emptyTelemetryAttributes
import com.lightningkite.services.telemetry.telemetryTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

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

@PublishedApi
internal class RequestTrace(val trace: TelemetryTrace) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestTrace>
}

/**
 * The span of the HTTP request or WebSocket phase being served, for attributes learned partway
 * through it (such as who the caller is), whichever nested span is current. Null outside a request.
 */
public suspend fun currentRequestTrace(): TelemetryTrace? = currentCoroutineContext()[RequestTrace]?.trace

context(engine: Engine)
internal suspend fun <T> reportingServerErrors(action: suspend () -> T): T = try {
    action()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    val status = (e as? HttpStatusException)?.status ?: HttpStatus.InternalServerError
    if (status.serverError) engine.reportException(e)
    throw e
}

/*
 * TODO: API Recommendations
 *
 * 1. The telemetry span names use different formats: "http.route" vs "WEBSOCKET.WILLCONNECT".
 *    Standardize naming conventions for consistency.
 */
