package com.lightningkite.lightningserver.engine.ktor

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.asSuspendingSource
import com.lightningkite.services.data.writeAll
import kotlinx.io.*
import kotlinx.serialization.Serializable
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

    // GET /sink — exercises the Data.Sink response branch in KtorEngine
    val sink = path.path("sink").get bind HttpHandler<PathSpec0> {
        HttpResponse(
            body = TypedData.sink(MediaType.Text.Plain) { out ->
                out.writeString("sink-content")
            },
            status = HttpStatus.OK,
        )
    }

    // GET /bigsink — large payload to verify buffered sink is fully flushed
    val bigSink = path.path("bigsink").get bind HttpHandler<PathSpec0> {
        val content = "x".repeat(100_000)
        HttpResponse(
            body = TypedData.sink(MediaType.Text.Plain) { out ->
                out.writeString(content)
            },
            status = HttpStatus.OK,
        )
    }

    // GET /chunkedsink — multiple writes to verify all chunks land in order
    val chunkedSink = path.path("chunkedsink").get bind HttpHandler<PathSpec0> {
        HttpResponse(
            body = TypedData.sink(MediaType.Application.OctetStream) { out ->
                out.writeString("alpha")
                out.writeString("|")
                out.writeString("beta")
                out.writeString("|")
                out.writeString("gamma")
            },
            status = HttpStatus.OK,
        )
    }

    // GET /suspendingsource — exercises the cooperative Data.Suspending response branch in KtorEngine
    val suspendingSource = path.path("suspendingsource").get bind HttpHandler<PathSpec0> {
        val content = "suspending-content"
        HttpResponse(
            body = TypedData.suspending(
                source = Buffer().also { it.writeString(content) }.asSuspendingSource(),
                mediaType = MediaType.Text.Plain,
                size = content.length.toLong(),
            ),
            status = HttpStatus.OK,
        )
    }

    // GET /suspendingproducer — large cooperative producer body; verifies full delivery + backpressure via SuspendingSink
    val suspendingProducer = path.path("suspendingproducer").get bind HttpHandler<PathSpec0> {
        val content = "y".repeat(100_000)
        HttpResponse(
            body = TypedData.suspendingProducer(MediaType.Text.Plain) { sink ->
                sink.writeAll(Buffer().also { it.writeString(content) })
            },
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
