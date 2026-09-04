package com.lightningkite.lightningserver.typedoutput

import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.instrument
import kotlinx.serialization.KSerializer

/**
 * Observes every typed value the server is about to send to a client, paired with the serializer
 * that will encode it.
 *
 * This is the last point at which outgoing data is still structured. Once it has been encoded it is
 * bytes, and no statement about *which fields* a client received can be made any more. That is why
 * disclosure auditing lives here rather than in an [com.lightningkite.lightningserver.http.HttpInterceptor].
 *
 * Every typed output passes through here — HTTP responses, WebSocket frames, and the sub-requests of
 * multiplexed requests alike. The one thing it cannot see is a handler that writes bytes directly
 * (raw `HttpHandler`s, signed file downloads), because there is no typed value to inspect.
 *
 * Observation only: an implementation may not alter the value. It *may* throw, which aborts the send
 * — a disclosure that could not be recorded must not happen.
 *
 * An implementation must tolerate being called many times per connection: a WebSocket pushes many
 * frames, and on a local engine they all belong to one socket.
 */
public interface TypedOutputInterceptor {
    /** The name of this interceptor, used for instrumentation and debugging. */
    public val name: String get() = this::class.simpleName ?: "anonymous"

    /**
     * Called with a value that is about to be serialized and sent.
     *
     * @param request The request or connection this output belongs to; correlate by
     *   [com.lightningkite.lightningserver.runtime.ServerRuntime.initiator].
     * @param serializer The serializer that will encode [value] — walk this rather than reflecting,
     *   so that what is observed is exactly what the client will receive.
     * @param value The value being sent.
     */
    context(runtime: ServerRuntime)
    public suspend fun <T> outputProduced(request: Request<*>, serializer: KSerializer<T>, value: T)
}

/**
 * Notifies every installed [TypedOutputInterceptor] that a typed value is about to be sent.
 *
 * Unlike the HTTP and WebSocket chains this is a flat list, not a nested chain: interceptors observe
 * rather than wrap, so there is nothing to short-circuit and no order-dependent post-processing.
 *
 * Returns immediately when nothing is installed, so servers that do not audit pay nothing per
 * response.
 *
 * Exceptions propagate to the caller, which is the point — see [TypedOutputInterceptor].
 */
context(server: ServerRuntime)
public suspend fun <T> emitTypedOutput(request: Request<*>, serializer: KSerializer<T>, value: T) {
    val interceptors = server.server.typedOutputInterceptors
    if (interceptors.isEmpty()) return
    for (interceptor in interceptors) {
        instrument(interceptor.name) { interceptor.outputProduced(request, serializer, value) }
    }
}
