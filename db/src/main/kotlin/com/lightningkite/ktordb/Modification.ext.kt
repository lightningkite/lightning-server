package com.lightningkite.ktordb


fun <T, V> Modification<T>.forFieldOrNull(field: DataClassProperty<T, V>): Modification<V>? {
    return when (this) {
        is Modification.Chain -> modifications.mapNotNull { it.forFieldOrNull(field) }.takeUnless { it.isEmpty() }
            ?.let { Modification.Chain(it) }
        is Modification.OnField<*, *> -> if (this.key == field) this.modification as Modification<V> else null
        else -> null
    }
}