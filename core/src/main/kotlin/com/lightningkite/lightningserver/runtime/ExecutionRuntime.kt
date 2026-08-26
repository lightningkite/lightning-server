package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.InternalLightningServerApi

/**
 * This engine, running one execution attributed to [initiator].
 *
 * Everything a runtime offers — settings, serialization, telemetry, task dispatch — is process-wide
 * and shared; only the attribution differs per execution, so this delegates the whole of it and adds
 * one property. Minted at the single seam every engine funnels through ([handle] and the
 * `*WithMetrics` helpers), never by user code, so that "who initiated this?" is answerable from the
 * runtime alone rather than reconstructed from whatever happens to be in scope.
 */
@InternalLightningServerApi
public fun Engine.forExecution(initiator: Initiator): ServerRuntime = ExecutionRuntime(this, initiator)

private class ExecutionRuntime(
    engine: Engine,
    override val initiator: Initiator,
) : ServerRuntime, Engine by engine

/**
 * The socket this execution is a phase of.
 *
 * For the handlers that wrap a socket in another — multiplexing, path rewriting — which have to name
 * the socket they are wrapping in order to derive the inner one's identity from it.
 *
 * @throws IllegalStateException off a WebSocket execution, where there is no socket to name.
 */
@InternalLightningServerApi
public val ServerRuntime.socketInitiator: Initiator.WebSocket
    get() = initiator as? Initiator.WebSocket
        ?: throw IllegalStateException(
            "Expected to be running a WebSocket phase, but this execution was initiated by $initiator."
        )
