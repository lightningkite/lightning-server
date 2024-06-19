package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContent
import kotlinx.serialization.KSerializer
import java.io.InputStream

class CsvFormatHandler(val csv: () -> CsvFormat) : StringFormatHandler(csv, ContentType.Text.CSV) {
    override suspend fun <T> streaming(contentType: ContentType, serializer: KSerializer<T>, value: T): HttpContent {
        return HttpContent.OutStream(
            write = { csv().encodeToAppendable(serializer, value, it.writer()) },
            length = null,
            type = contentType
        )
    }

    override suspend fun <T> fromStream(contentType: ContentType, stream: InputStream, serializer: KSerializer<T>): T {
        return csv().decodeFromReader(serializer, stream.bufferedReader())
    }
}