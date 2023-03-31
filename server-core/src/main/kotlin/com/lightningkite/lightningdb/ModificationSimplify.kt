package com.lightningkite.lightningdb

import kotlin.reflect.KProperty1

fun <T> Modification<T>.simplify(): Modification<T> {
    return if (this is Modification.Chain) {
        val flattened = this.modifications.map { it.simplify() }.flatMap {
            (it as? Modification.Chain)?.modifications ?: listOf(it)
        }
        val lastAssignment = flattened.indexOfLast { it is Modification.Assign }
        if (lastAssignment == -1) Modification.Chain(flattened)
        else {
            var value = (flattened[lastAssignment] as Modification.Assign<T>).value
            for (mod in flattened.subList(lastAssignment + 1, flattened.size)) {
                value = mod(value)
            }
            Modification.Assign(value)
        }
    } else if (this is Modification.OnField<*, *>) {
        Modification.OnField<T, Any?>(
            key as KProperty1<T, Any?>,
            modification.simplify() as Modification<Any?>
        ) as Modification<T>
    } else this
}
