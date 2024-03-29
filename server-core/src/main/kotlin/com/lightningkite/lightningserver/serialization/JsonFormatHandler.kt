package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.InputStream

class JsonFormatHandler(val json: () -> Json, val jsonWithoutDefaults: () -> Json) :
    StringFormatHandler(json, ContentType.Application.Json) {
    override suspend fun <T> invoke(contentType: ContentType, serializer: KSerializer<T>, value: T): HttpContent {
        return if (contentType.parameters["defaults"] == "false") HttpContent.Text(
            jsonWithoutDefaults().encodeToString(serializer, value),
            contentType
        ) else super.invoke(contentType, serializer, value)
    }

    override suspend fun <T> streaming(contentType: ContentType, serializer: KSerializer<T>, value: T): HttpContent {
        return HttpContent.OutStream(
            write = {
                (if (contentType.parameters["defaults"] == "false") jsonWithoutDefaults() else json()).encodeToStream(
                    serializer,
                    value,
                    it
                )
            },
            length = null,
            type = contentType
        )
    }

    override suspend fun <T> fromStream(contentType: ContentType, stream: InputStream, serializer: KSerializer<T>): T {
        return json().decodeFromStream(serializer, stream)
    }
}