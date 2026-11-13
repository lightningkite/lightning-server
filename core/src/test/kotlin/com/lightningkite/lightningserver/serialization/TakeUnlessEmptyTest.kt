package com.lightningkite.lightningserver.serialization

import com.lightningkite.services.data.AbstractSuspendingSource
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TakeUnlessEmptyTest {

    /** A body of unknown size that arrives one piece per read, and records being let go of. */
    private class PiecesSource(vararg pieces: String) : AbstractSuspendingSource() {
        private val remaining = ArrayDeque(pieces.toList())
        var released = false
            private set

        override suspend fun fill(into: Buffer): Long {
            val piece = remaining.removeFirstOrNull() ?: return -1L
            val before = into.size
            into.writeString(piece)
            return into.size - before
        }

        override fun release(cause: Throwable?) {
            released = true
        }
    }

    private fun streamed(source: PiecesSource) = TypedData.suspendingSource(source, MediaType.Text.Plain)

    @Test
    fun `a body known to be empty is no body`() = runBlocking {
        assertNull(TypedData.text("", MediaType.Text.Plain).takeUnlessEmpty())
    }

    @Test
    fun `a body known to hold something is returned as it is`() = runBlocking {
        val body = TypedData.text("abc", MediaType.Text.Plain)
        assertSame(body, body.takeUnlessEmpty())
    }

    @Test
    fun `a body of unknown size that ends at once is no body`() = runBlocking {
        val source = PiecesSource()
        assertNull(streamed(source).takeUnlessEmpty())
        assertTrue(source.released, "the empty body's source was left open")
    }

    @Test
    fun `a body of unknown size keeps everything it holds`() = runBlocking {
        val body = assertNotNull(streamed(PiecesSource("ab", "cd", "e")).takeUnlessEmpty())
        assertEquals(MediaType.Text.Plain, body.mediaType)
        assertEquals("abcde", body.text())
    }

    @Test
    fun `closing the body returned lets go of the one it came from`() = runBlocking {
        val source = PiecesSource("ab", "cd")
        val body = assertNotNull(streamed(source).takeUnlessEmpty())
        body.close()
        assertTrue(source.released, "the original source was left open")
    }
}
