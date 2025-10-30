package com.lightningkite.lightningserver.engine.ktor

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketTopic
import com.lightningkite.services.data.TypedData
import kotlinx.io.Source
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.Serializable
import com.lightningkite.lightningserver.plainText
import java.io.ByteArrayInputStream

/**
 * Minimal server definition for testing KtorEngine HTTP and WebSocket adaptation.
 */
object TestServerBuilder : ServerBuilder() {
    // Simple GET /text
    val text = path.path("text").get bind HttpHandler<PathSpec0> {
        HttpResponse.plainText(
            text = "Hello KtorEngine",
            status = HttpStatus.OK,
            headers = HttpHeaders {
                add("X-Test", "A")
                add("X-Multi", "B")
                add("X-Multi", "C")
            }
        )
    }

    // GET /bytes
    val bytes = path.path("bytes").get bind HttpHandler<PathSpec0> {
        val b = "0123456789".encodeToByteArray()
        HttpResponse(
            body = TypedData.bytes(b, MediaType.Application.OctetStream),
            status = HttpStatus.OK,
        )
    }

    // GET /source
    val source = path.path("source").get bind HttpHandler<PathSpec0> {
        val content = "streamed-content"
        val src: Source = ByteArrayInputStream(content.toByteArray()).asSource().buffered()
        HttpResponse(
            body = TypedData.source(src, MediaType.Text.Plain, content.length.toLong()),
            status = HttpStatus.OK,
        )
    }

    // GET /empty triggers 204 branch with preset CT + CL headers
    val empty = path.path("empty").get bind HttpHandler<PathSpec0> {
        HttpResponse(
            body = null,
            status = HttpStatus.NoContent,
            headers = HttpHeaders {
                add(HttpHeader.ContentType, "text/plain; charset=UTF-8")
                add(HttpHeader.ContentLength, "0")
            }
        )
    }

    // WebSocket /ws
    @Serializable
    data class WSState(val connected: Boolean = false)

    val wsTopic: WebSocketTopic<PathSpec0, String> = path.path("ws").topic(kotlinx.serialization.serializer<String>())

    val ws = path.path("ws") bind WebSocketHandler<PathSpec0, WSState>(
        willConnect = {
            WSState(false)
        },
        didConnect = {
            send(WebSocketFrame.Text("didConnect"))
            updateStateImmediately { it.copy(connected = true) }
        },
        messageFromClient = { frame ->
            when (frame) {
                is WebSocketFrame.Text -> send(WebSocketFrame.Text("echo:" + frame.content))
                is WebSocketFrame.Binary -> send(WebSocketFrame.Binary(frame.content))
            }
        },
        topicHandlers = {
            wsTopic bind { topic ->
                send(WebSocketFrame.Text("topic:" + topic.value))
            }
        },
        disconnect = { reason ->
            send(WebSocketFrame.Text("closed:" + reason.code))
        }
    )
}
