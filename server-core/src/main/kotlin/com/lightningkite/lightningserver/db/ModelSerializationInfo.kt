package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

inline fun <reified T : HasId<ID>, reified ID : Comparable<ID>> ModelSerializationInfo() =
    ModelSerializationInfo<T, ID>(
        serializer = Serialization.module.serializer(),
        idSerializer = Serialization.module.serializer(),
    )

data class ModelSerializationInfo<T : HasId<ID>, ID : Comparable<ID>>(
    val serializer: KSerializer<T>,
    val idSerializer: KSerializer<ID>
)
