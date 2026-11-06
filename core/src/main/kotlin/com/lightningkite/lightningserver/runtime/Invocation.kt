package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest

/**
 * An execution about to run, paired with the request that started it when it has one.
 *
 * This is what an [ExecutionInterceptor] sees. The request is held in memory only: [execution] is what
 * gets serialized into task queues and socket rows, and it stays small by never carrying a request.
 */
public sealed interface Invocation {
    public val execution: Execution

    public sealed interface Requested : Invocation {
        public val request: Request<*>

        /**
         * Whether this runs inside another request's execution, such as a `/meta/bulk` part, a multiplexed
         * socket channel, or an endpoint called from server code.
         *
         * It is `false` for the physical request or socket the engine received.
         */
        public val virtual: Boolean
    }

    /** An HTTP request, whether a client sent it or it was dispatched inside another execution. */
    public data class Http(
        override val execution: Execution.Http,
        override val request: HttpRequest<*>,
        val origin: Origin,
    ) : Invocation, Requested {
        override val virtual: Boolean get() = origin != Origin.Engine

        /** Who asked for an HTTP request. */
        public enum class Origin {
            /** The engine received the request. */
            Engine,

            /** A client asked for it inside another request, such as one part of a `/meta/bulk` call. */
            Client,

            /** Server code called the endpoint. */
            Server,
        }
    }

    /** One phase of a socket, with the request that opened it. */
    public data class WebSocket(
        override val execution: Execution.WebSocket,
        override val request: WebSocketConnectRequest<*>,
        override val virtual: Boolean,
    ) : Invocation, Requested

    /** A task, a schedule tick, a startup or pre-deploy task, or a direct execution. */
    public data class Unrequested(
        override val execution: Execution.Unrequested
    ) : Invocation
}
