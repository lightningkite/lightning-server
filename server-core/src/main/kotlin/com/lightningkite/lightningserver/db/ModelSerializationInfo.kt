package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.serializableProperties
import com.lightningkite.lightningdb.contextualSerializerIfHandled
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.KSerializer

inline fun <reified T : HasId<ID>, reified ID : Comparable<ID>> ModelSerializationInfo(): ModelSerializationInfo<T, ID> {
    val ser = Serialization.module.contextualSerializerIfHandled<T>()
    @Suppress("UNCHECKED_CAST")
    return ModelSerializationInfo<T, ID>(
        serializer = ser,
        idSerializer = (ser.serializableProperties?.find { it.name == "_id" }?.serializer as? KSerializer<ID>) ?: Serialization.module.contextualSerializerIfHandled(),
    )
}

data class ModelSerializationInfo<T : HasId<ID>, ID : Comparable<ID>>(
    val serializer: KSerializer<T>,
    val idSerializer: KSerializer<ID>
)
