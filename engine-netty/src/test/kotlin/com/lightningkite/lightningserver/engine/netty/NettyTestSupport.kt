package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.engine.local.engineCache
import com.lightningkite.lightningserver.engine.local.enginePubSub
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Gives the settings every engine has their defaults, leaving a test to set only the ones it is about. */
internal fun NettyEngine.useFrameworkDefaults() {
    settings.run {
        generalSettings.useDefault()
        secretBasis.useDefault()
        telemetrySettings.useDefault()
        loggingSettings.useDefault()
        enginePubSub.useDefault()
        engineCache.useDefault()
    }
}

/**
 * Starts this engine on a thread of its own, since [NettyEngine.start] blocks, and returns the port it
 * bound. Configure port 0 to have the OS pick a free one. Fails if the engine is not listening within
 * five seconds.
 */
internal fun NettyEngine.startInBackground(): Int {
    val startupFailure = AtomicReference<Throwable>()
    Thread {
        try {
            start()
        } catch (t: Throwable) {
            startupFailure.set(t)
        }
    }.start()
    val deadline = System.currentTimeMillis() + 5_000
    while (boundAddress == null && startupFailure.get() == null && System.currentTimeMillis() < deadline) {
        Thread.sleep(20)
    }
    startupFailure.get()?.let { throw IllegalStateException("NettyEngine failed to start", it) }
    return checkNotNull(boundAddress) { "NettyEngine failed to start within 5 seconds" }.port
}

/**
 * Opens a WebSocket to [url] and waits for it to connect, failing if it has not within five seconds.
 *
 * @param onHandshake given the server's handshake response, before this returns.
 * @param onClosed given the code of the server's own close frame. A dropped connection is a failure instead.
 */
internal fun OkHttpClient.openWebSocket(
    url: String,
    onHandshake: (Response) -> Unit = {},
    onText: (String) -> Unit = {},
    onClosed: (code: Int) -> Unit = {},
    onFailure: (Throwable) -> Unit = {},
): WebSocket {
    val opened = CompletableFuture<Unit>()
    val ws = newWebSocket(
        Request.Builder().url(url).build(),
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onHandshake(response)
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) = onText(text)

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed(code)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.completeExceptionally(t)
                onFailure(t)
            }
        },
    )
    opened.get(5, TimeUnit.SECONDS)
    return ws
}
