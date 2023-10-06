package com.lightningkite.lightningserver.serialization

import kotlinx.serialization.*
import java.util.Base64

public fun <T> BinaryFormat.decodeFromBase64(deserializer: DeserializationStrategy<T>, base64: String): T =
    decodeFromByteArray(deserializer, Base64.getDecoder().decode(base64))

public inline fun <reified T> BinaryFormat.decodeFromBase64(base64: String): T =
    decodeFromByteArray(serializersModule.serializer(), Base64.getDecoder().decode(base64))

public fun <T> BinaryFormat.encodeToBase64(serializer: SerializationStrategy<T>, value: T): String =
    Base64.getEncoder().encodeToString(encodeToByteArray(serializer, value))

public inline fun <reified T> BinaryFormat.encodeToBase64(value: T): String =
    Base64.getEncoder().encodeToString(encodeToByteArray(serializersModule.serializer(), value))


public fun <T> BinaryFormat.decodeFromBase64Url(deserializer: DeserializationStrategy<T>, base64: String): T =
    decodeFromByteArray(deserializer, Base64.getUrlDecoder().decode(base64))

public inline fun <reified T> BinaryFormat.decodeFromBase64Url(base64: String): T =
    decodeFromByteArray(serializersModule.serializer(), Base64.getUrlDecoder().decode(base64))

public fun <T> BinaryFormat.encodeToBase64Url(serializer: SerializationStrategy<T>, value: T): String =
    Base64.getUrlEncoder().encodeToString(encodeToByteArray(serializer, value))

public inline fun <reified T> BinaryFormat.encodeToBase64Url(value: T): String =
    Base64.getUrlEncoder().encodeToString(encodeToByteArray(serializersModule.serializer(), value))
