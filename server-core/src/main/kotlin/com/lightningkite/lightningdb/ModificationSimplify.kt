package com.lightningkite.lightningdb

import com.lightningkite.serialization.*
import com.lightningkite.serialization.SerializableProperty

fun <T> Modification<T>.simplify(): Modification<T> {
    return if (this is Modification.Chain) {
        if(modifications.isEmpty()) return Modification.Nothing<T>()
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
        @Suppress("UNCHECKED_CAST")
        Modification.OnField(
            key as SerializableProperty<T, Any?>,
            modification.simplify() as Modification<Any?>
        )
    } else this
}
