@file:SharedCode
package com.lightningkite.ktordb

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.khrysalis.fatalError
import kotlinx.serialization.Serializable

@DoNotGenerateFields
@Serializable(ForeignKeySerializer::class)
data class ForeignKey<Model: HasId<ID>, ID: Comparable<ID>>(val id: ID)

object ForeignKeyFields {
    fun <T: HasId<ID>, ID: Comparable<ID>> id() = DataClassProperty<ForeignKey<T, ID>, ID>(
        name = "id",
        get = { it.id },
        set = { _, _ -> fatalError() },
        compare = compareBy { it.id }
    )
}

val <K, T: HasId<ID>, ID: Comparable<ID>> PropChain<K, ForeignKey<T, ID>>.id: PropChain<K, ID> get() = this[ForeignKeyFields.id()]
