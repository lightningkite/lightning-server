package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

inline fun <reified USER, reified T : HasId<ID>, reified ID : Comparable<ID>> ModelSerializationInfo() =
    ModelSerializationInfo<USER, T, ID>(
        authRequirement = AuthRequirement(),
        serializer = Serialization.module.serializer(),
        idSerializer = Serialization.module.serializer(),
    )

data class ModelSerializationInfo<USER, T : HasId<ID>, ID : Comparable<ID>>(
    val authRequirement: AuthRequirement<USER>,
    val serializer: KSerializer<T>,
    val idSerializer: KSerializer<ID>
)
