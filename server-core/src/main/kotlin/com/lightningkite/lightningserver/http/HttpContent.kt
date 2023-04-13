package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.files.FileObject
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.html.HTML
import kotlinx.html.html
import kotlinx.html.stream.appendHTML
import kotlinx.serialization.encodeToString
import java.io.*
import java.nio.charset.Charset

sealed class HttpContent {
    abstract suspend fun stream(): InputStream
    suspend fun text(): String = stream().reader().readText()
    abstract val length: Long?
    abstract val type: ContentType

    data class Text(val string: String, override val type: ContentType) : HttpContent() {
        val bytes = string.toByteArray(Charset.defaultCharset())
        override suspend fun stream(): InputStream = ByteArrayInputStream(bytes)
        override val length: Long get() = bytes.size.toLong()
    }

    class Binary(val bytes: ByteArray, override val type: ContentType) : HttpContent() {
        override val length: Long get() = bytes.size.toLong()
        override suspend fun stream(): InputStream = ByteArrayInputStream(bytes)
        override fun toString(): String = "Binary(${bytes.size} bytes)"
        override fun hashCode(): Int = bytes.contentHashCode()
        override fun equals(other: Any?): Boolean = other is Binary && other.bytes.contentEquals(this.bytes)
    }

    data class Stream(
        val getStream: suspend () -> InputStream,
        override val length: Long? = null,
        override val type: ContentType
    ) : HttpContent() {
        override suspend fun stream(): InputStream = getStream()
    }

    data class OutStream(
        val write: (OutputStream) -> Unit,
        override val length: Long? = null,
        override val type: ContentType
    ) : HttpContent() {
        override suspend fun stream(): InputStream {
            val bytes = ByteArrayOutputStream()
            write(bytes)
            return ByteArrayInputStream(bytes.toByteArray())
        }
    }

    data class Multipart(val parts: Flow<Part>, override val type: ContentType) : HttpContent() {
        override val length: Long?
            get() = null

        override suspend fun stream(): InputStream = throw UnsupportedOperationException()
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

        inline fun <reified T> Json(
            value: T
        ): Text = HttpContent.Text(
            string = Serialization.json.encodeToString(value),
            type = ContentType.Application.Json
        )

        fun file(file: File, type: ContentType = ContentType.fromExtension(file.extension)): Stream {
            return Stream(
                getStream = { file.inputStream() },
                length = file.length(),
                type = type
            )
        }

        @OptIn(DelicateCoroutinesApi::class)
        suspend fun file(file: FileObject): Stream {
            val info = GlobalScope.async(start = CoroutineStart.LAZY) { file.info()!! }
            return Stream(
                getStream = { file.read() },
                length = info.await().size,
                type = info.await().type
            )
        }
    }
}

//1:10pm stop
//2:06pm start