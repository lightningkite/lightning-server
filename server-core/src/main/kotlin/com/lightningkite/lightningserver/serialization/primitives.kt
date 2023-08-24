package com.lightningkite.lightningserver.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

fun <T> Json.encodeUnwrappingString(serializer: KSerializer<T>, value: T): String = when {
    serializer.descriptor.kind == PrimitiveKind.STRING && !serializer.descriptor.isNullable -> encodeToJsonElement(serializer, value).jsonPrimitive.content
    else -> encodeToString(serializer, value)
}
@Suppress("UNCHECKED_CAST")
fun <T> Json.decodeUnwrappingString(serializer: KSerializer<T>, value: String): T = when {
    serializer.descriptor.kind == PrimitiveKind.STRING && !serializer.descriptor.isNullable -> decodeFromJsonElement(serializer, JsonPrimitive(value))
    else -> decodeFromString(serializer, value)
}