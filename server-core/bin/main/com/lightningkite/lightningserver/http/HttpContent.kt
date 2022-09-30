package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.core.ContentType
import kotlinx.coroutines.flow.Flow
import kotlinx.html.HTML
import kotlinx.html.html
import kotlinx.html.stream.appendHTML
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.Charset

sealed class HttpContent {
    abstract fun stream(): InputStream
    suspend fun text(): String = stream().reader().readText()
    abstract val length: Long?
    abstract val type: ContentType

    data class Text(val string: String, override val type: ContentType) : HttpContent() {
        val bytes = string.toByteArray(Charset.defaultCharset())
        override fun stream(): InputStream = ByteArrayInputStream(bytes)
        override val length: Long get() = bytes.size.toLong()
    }

    class Binary(val bytes: ByteArray, override val type: ContentType) : HttpContent() {
        override val length: Long get() = bytes.size.toLong()
        override fun stream(): InputStream = ByteArrayInputStream(bytes)
        override fun toString(): String = "Binary(${bytes.size} bytes)"
        override fun hashCode(): Int = bytes.contentHashCode()
        override fun equals(other: Any?): Boolean = other is Binary && other.bytes.contentEquals(this.bytes)
    }

    data class Stream(
        val getStream: () -> InputStream,
        override val length: Long? = null,
        override val type: ContentType
    ) : HttpContent() {
        override fun stream(): InputStream = getStream()
    }

    data class OutStream(
        val write: (OutputStream) -> Unit,
        override val length: Long? = null,
        override val type: ContentType
    ) : HttpContent() {
        override fun stream(): InputStream {
            val bytes = ByteArrayOutputStream()
            write(bytes)
            return ByteArrayInputStream(bytes.toByteArray())
        }
    }

    data class Multipart(val parts: Flow<Part>, override val type: ContentType) : HttpContent() {
        override val length: Long?
            get() = null

        override fun stream(): InputStream = throw UnsupportedOperationException()
        sealed class Part {
            data class FormItem(val key: String, val value: String) : Part()
            data class DataItem(
                val key: String,
                val filename: String,
                val headers: HttpHeaders,
                val content: HttpContent
            ) : Part()
        }
    }

    companion object {
        fun Html(
            body: HTML.() -> Unit,
        ): OutStream = HttpContent.OutStream(
            write = {
                it.writer().use {
                    it.write("<!DOCTYPE html>\n")
                    it.appendHTML().html(block = body)
                }
            },
            type = ContentType.Text.Html,
            length = null
        )
    }
}

//1:10pm stop
//2:06pm start