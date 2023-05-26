package com.lightningkite.lightningdb

import kotlin.reflect.KProperty1


fun <T, V> Modification<T>.forFieldOrNull(field: KProperty1<T, V>): Modification<V>? {
    return when (this) {
        is Modification.Chain -> modifications.mapNotNull { it.forFieldOrNull(field) }.takeUnless { it.isEmpty() }
            ?.let { Modification.Chain(it) }

        is Modification.OnField<*, *> -> if (this.key == field) this.modification as Modification<V> else null
        else -> null
    }
}

fun <T, V> Modification<T>.vet(field: KProperty1<T, V>, onModification: (Modification<V>) -> Unit) {
    when (this) {
        is Modification.Assign -> onModification(Modification.Assign(field.get(this.value)))
        is Modification.Chain -> modifications.forEach { it.vet(field, onModification) }
        is Modification.OnField<*, *> -> if (this.key == field) (this.modification as Modification<V>).vet(
            onModification
        ) else null

        else -> {}
    }
}

fun <T> Modification<T>.vet(onModification: (Modification<T>) -> Unit) {
    when (this) {
        is Modification.Chain -> modifications.forEach { it.vet(onModification) }
        else -> onModification(this)
    }
}

@Suppress("UNCHECKED_CAST")
fun <V> Modification<*>.vet(fieldChain: List<KProperty1<*, *>>, onModification: (Modification<V>) -> Unit) {
    val field: KProperty1<*, *> = fieldChain.firstOrNull() ?: run {
        onModification(this as Modification<V>)
        return
    }
    when (this) {
        is Modification.Assign -> {
            var value = this.value
            for (key in fieldChain) {
                value = (key as KProperty1<Any?, Any?>).get(value)
            }
            onModification(Modification.Assign(value as V))
        }

        is Modification.Chain -> modifications.forEach { it.vet(fieldChain, onModification) }
        is Modification.OnField<*, *> -> if (this.key == field) this.modification.vet(
            fieldChain.drop(1),
            onModification
        )

        is Modification.IfNotNull -> this.modification.vet(fieldChain, onModification)
        else -> {}
    }
}

fun <T, V> Modification<T>.map(
    field: KProperty1<T, V>,
    onModification: (Modification<V>) -> Modification<V>,
): Modification<T> {
    return when (this) {
        is Modification.Chain -> modifications.map { it.map(field, onModification) }.let { Modification.Chain(it) }
        is Modification.OnField<*, *> -> if (this.key == field) (this as Modification.OnField<T, V>).copy(
            modification = onModification(
                modification
            )
        ) else this as Modification<T>

        is Modification.Assign -> Modification.Assign(
            field.setCopy(
                this.value,
                onModification(Modification.Assign(field.get(this.value))).let { it as Modification.Assign<V> }.value
            )
        )

        else -> this
    }
}

suspend fun <T, V> Modification<T>.mapSuspend(
    field: KProperty1<T, V>,
    onModification: suspend (Modification<V>) -> Modification<V>,
): Modification<T> {
    return when (this) {
        is Modification.Chain -> modifications.map { it.mapSuspend(field, onModification) }.let { Modification.Chain(it) }
        is Modification.OnField<*, *> -> if (this.key == field) (this as Modification.OnField<T, V>).copy(
            modification = onModification(
                modification
            )
        ) else this as Modification<T>

        is Modification.Assign -> Modification.Assign(
            field.setCopy(
                this.value,
                onModification(Modification.Assign(field.get(this.value))).let { it as Modification.Assign<V> }.value
            )
        )

        else -> this
    }
}