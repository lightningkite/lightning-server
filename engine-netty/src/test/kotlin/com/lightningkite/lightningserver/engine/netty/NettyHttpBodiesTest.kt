package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.HttpVersion
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What the body plumbing owes the resources handed to it when a request does not run its course. */
class NettyHttpBodiesTest {

    @Test
    fun `a chunk handed to a reader that is then cancelled is released`() = runBlocking {
        val source = RequestBodySource(EmbeddedChannel().eventLoop(), maxBody = Long.MAX_VALUE) {}
        val reader = launch { source.read(Buffer()) }
        // Let the reader start waiting for a chunk.
        yield()

        val chunk = Unpooled.buffer().writeBytes(ByteArray(16))
        assertTrue(source.offer(chunk))
        // The chunk is now on its way to the reader, which is cancelled before it gets to take it.
        reader.cancelAndJoin()

        chunk.release()
        assertEquals(0, chunk.refCnt(), "the chunk the cancelled reader never took was not released")
    }

    @Test
    fun `a streamed body is closed when its response cannot be started`() = runBlocking {
        val channel = EmbeddedChannel().apply { close() }
        var closed = false
        val source = object : RawSource {
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = -1L
            override fun close() {
                closed = true
            }
        }
        val response = HttpResponse(TypedData.source(source.buffered(), MediaType.Text.Plain))

        assertFailsWith<IOException> {
            sendResponse(
                channel, response, HttpVersion.HTTP_1_1,
                isHead = false, reusable = true, streaming = true, onCommitted = {},
            )
        }
        assertTrue(closed, "the body was never started, and nothing closed it")
    }
}
