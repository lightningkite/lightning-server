package com.lightningkite.lightningserver.serialization

import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.capturedKClass
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

fun <T> Json.encodeUnwrappingString(serializer: KSerializer<T>, value: T): String {
    val fullSerializer = if(serializer is ContextualSerializer<*>) serializersModule.getContextual(serializer.descriptor.capturedKClass!!) as KSerializer<T> else serializer
    return when {
        fullSerializer.descriptor.kind == PrimitiveKind.STRING && !fullSerializer.descriptor.isNullable -> encodeToJsonElement(
            fullSerializer,
            value
        ).jsonPrimitive.content

        else -> encodeToString(serializer, value)
    }
}

@Suppress("UNCHECKED_CAST")
fun <T> Json.decodeUnwrappingString(serializer: KSerializer<T>, value: String): T {
    val fullSerializer = if(serializer is ContextualSerializer<*>) serializersModule.getContextual(serializer.descriptor.capturedKClass!!) as KSerializer<T> else serializer
    return when {
        fullSerializer.descriptor.kind == PrimitiveKind.STRING && !fullSerializer.descriptor.isNullable -> decodeFromJsonElement(
            fullSerializer,
            JsonPrimitive(value)
        )

        else -> decodeFromString(serializer, value)
    }
}