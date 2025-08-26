package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.capturedKClass
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive


context(server: ServerRuntime)
public fun <ID : Comparable<ID>> PrincipalType<*, ID>.idString(id: ID): String =
    server.internalSerialization.stringArrayFormat.encodeToString(idSerializer, id)

context(server: ServerRuntime)
public suspend fun <T : HasId<ID>, ID : Comparable<ID>> PrincipalType<T, ID>.findUserIdString(
    property: String,
    value: String,
): String? {
    return fetchByProperty(property, value)?.let { idString(it._id) }
}

//@Suppress("UNCHECKED_CAST")
//private fun <T> Json.decodeUnwrappingString(serializer: KSerializer<T>, value: String): T {
//    @Suppress("UNCHECKED_CAST")
//    val fullSerializer =
//        if (serializer.descriptor.kind == SerialKind.CONTEXTUAL) serializersModule.getContextual(serializer.descriptor.capturedKClass!!) as KSerializer<T> else serializer
//    return when {
//        fullSerializer.descriptor.kind == PrimitiveKind.STRING && !fullSerializer.descriptor.isNullable -> decodeFromJsonElement(
//            fullSerializer,
//            JsonPrimitive(value)
//        )
//
//        else -> decodeFromString(serializer, value)
//    }
//}