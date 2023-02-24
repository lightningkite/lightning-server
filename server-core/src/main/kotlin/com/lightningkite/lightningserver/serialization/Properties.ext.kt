package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.http.HttpContent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.properties.Properties
import kotlinx.serialization.serializer
import java.net.URLDecoder
import java.net.URLEncoder

inline fun <reified T> Properties.decodeFromFormData(value: String): T = decodeFromFormData(serializersModule.serializer<T>(), value)
fun <T> Properties.decodeFromFormData(serializer: KSerializer<T>, value: String): T {
    return decodeFromStringMap<T>(
        serializer,
        value.split('&').associate { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
    )
}
inline fun <reified T> Properties.encodeToFormData(value: T): String = encodeToFormData(serializersModule.serializer<T>(), value)
fun <T> Properties.encodeToFormData(serializer: KSerializer<T>, value: T): String {
    return encodeToStringMap<T>(
        serializer,
        value
    ).entries.joinToString("&") { URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + URLEncoder.encode(it.value, Charsets.UTF_8) }
}