package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.files.FileObject
import com.lightningkite.lightningserver.files.download
import com.lightningkite.lightningserver.serialization.Serialization
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.html.HTML
import kotlinx.html.html
import kotlinx.html.stream.appendHTML
import kotlinx.serialization.encodeToString
import java.io.*
import java.nio.charset.Charset
import java.util.Enumeration

sealed class HttpContent {
    abstract suspend fun stream(): InputStream
    suspend fun text(): String = stream().use { it.reader().readText() }
    suspend fun bytes(): ByteArray = stream().use { it.readBytes() }
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
        override val type: ContentType,
    ) : HttpContent() {
        override suspend fun stream(): InputStream = getStream()
    }

    data class OutStream(
        val write: (OutputStream) -> Unit,
        override val length: Long? = null,
        override val type: ContentType,
    ) : HttpContent() {
        override suspend fun stream(): InputStream {
            val bytes = ByteArrayOutputStream()
            write(bytes)
            return ByteArrayInputStream(bytes.toByteArray())
        }
    }

    data class Multipart(override val type: ContentType, val parts: Flow<HttpContentAndHeaders>) : HttpContent() {
        override val length: Long?
            get() = null

        override suspend fun stream(): InputStream = throw UnsupportedOperationException()

        companion object Part {
            @Deprecated("Use lowercase function instead", ReplaceWith("formItem(key, value)"))
            fun FormItem(key: String, value: String) = formItem(key, value)

            @Deprecated("Use lowercase function instead", ReplaceWith("dataItem(key, filename, headers, content)"))
            fun DataItem(
                key: String,
                filename: String,
                headers: HttpHeaders,
                content: HttpContent,
            ) = dataItem(key, filename, headers, content)

            fun formItem(key: String, value: String): HttpContentAndHeaders = HttpContentAndHeaders(
                headers = HttpHeaders {
                    set(HttpHeader.ContentDisposition, HttpHeaderValue("form-data", mapOf("name" to key)))
                },
                content = HttpContent.Text(value, ContentType.Text.Plain)
            )

            fun dataItem(
                key: String,
                filename: String,
                headers: HttpHeaders,
                content: HttpContent,
            ) = HttpContentAndHeaders(
                headers = HttpHeaders {
                    set(headers)
                    set(
                        HttpHeader.ContentDisposition,
                        HttpHeaderValue("form-data", mapOf("name" to key, "filename" to filename))
                    )
                },
                content = content
            )
        }
    }

    companion object {
        @Deprecated("Use the lowercase version", ReplaceWith("html"))
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

        @Deprecated("Use the lowercase version", ReplaceWith("json"))
        inline fun <reified T> Json(
            value: T,
        ): Text = HttpContent.Text(
            string = Serialization.json.encodeToString(value),
            type = ContentType.Application.Json
        )

        fun html(
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

        inline fun <reified T> json(
            value: T,
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

        suspend fun file(file: FileObject) = file.get()!!
    }
}

private fun <T> Iterator<T>.toEnumeration(): Enumeration<T> {
    return object : Enumeration<T> {
        override fun hasMoreElements(): Boolean = this@toEnumeration.hasNext()
        override fun nextElement(): T = this@toEnumeration.next()
    }
}

suspend fun HttpContent.download(
    destination: File = File.createTempFile(
        "temp",
        type.extension,
    ),
): File {
    destination.outputStream().use { out ->
        stream().use {
            it.copyTo(out)
        }
    }
    return destination
}