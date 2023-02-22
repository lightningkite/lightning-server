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
        return properties().decodeFromFormData(serializer, content.text())
    }
    override suspend fun <T> invoke(contentType: ContentType, serializer: KSerializer<T>, value: T): HttpContent {
        return HttpContent.Text(
            properties().encodeToFormData(serializer, value),
            contentType
        )
    }
}