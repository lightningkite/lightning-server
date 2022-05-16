package com.lightningkite.ktordb


fun <T, V> Modification<T>.forFieldOrNull(field: DataClassProperty<T, V>): Modification<V>? {
    return when (this) {
        is Modification.Chain -> modifications.mapNotNull { it.forFieldOrNull(field) }.takeUnless { it.isEmpty() }
            ?.let { Modification.Chain(it) }
        is Modification.OnField<*, *> -> if (this.key == field) this.modification as Modification<V> else null
        else -> null
    }
}

fun <T, V> Modification<T>.vet(field: DataClassProperty<T, V>, onModification: (Modification<V>) -> Unit) {
    when (this) {
        is Modification.Assign -> onModification(Modification.Assign(field[this.value]))
        is Modification.Chain -> modifications.forEach { it.vet(field, onModification) }
        is Modification.OnField<*, *> -> if (this.key == field) (this.modification as Modification<V>).vet(onModification) else null
        else -> { }
    }
}

fun <T> Modification<T>.vet(onModification: (Modification<T>) -> Unit) {
    when (this) {
        is Modification.Chain -> modifications.forEach { it.vet(onModification) }
        else -> onModification(this)
    }
}