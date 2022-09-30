package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.HttpContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.StringFormat
import kotlinx.serialization.properties.Properties
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder

open class FormDataHandler(
    val properties: () -> Properties
) : Serialization.HttpContentHandler {
    override val contentType: ContentType = ContentType.Application.FormUrlEncoded
    override suspend fun <T> invoke(content: HttpContent, serializer: KSerializer<T>): T {
        return properties().decodeFromStringMap<T>(
            serializer,
            content.text().split('&').associate { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        )
    }
    override suspend fun <T> invoke(contentType: ContentType, serializer: KSerializer<T>, value: T): HttpContent {
        return HttpContent.Text(
            properties().encodeToStringMap<T>(
                serializer,
                value
            ).entries.joinToString("&") { URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + URLEncoder.encode(it.value, Charsets.UTF_8) },
            contentType
        )
    }
}