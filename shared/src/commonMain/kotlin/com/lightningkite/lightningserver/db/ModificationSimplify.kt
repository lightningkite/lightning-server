package com.lightningkite.lightningserver.db

import com.lightningkite.serialization.SerializableProperty

private fun <T> List<Modification<T>>.toMod(): Modification<T> {
    val listWithoutNothing = this.filter { !it.isNothing }
    if(listWithoutNothing.isEmpty()) return Modification.Nothing()
    if(listWithoutNothing.size == 1) return listWithoutNothing[0]
    return Modification.Chain(listWithoutNothing)
//    val assignmentOpIndex = modifications.indexOfLast { it is Modification.Assign }
}

fun <T> Modification<T>.simplify(): Modification<T> {
    return when(this) {
        is Modification.Chain -> {
            val flat = ArrayList<Modification<T>>()
            fun Modification.Chain<T>.traverse() {
                for(item in modifications) {
                    if(item is Modification.Chain) item.traverse()
                    else flat.add(item)
                }
            }
            traverse()
            val assignmentOpIndex = flat.indexOfLast { it is Modification.Assign }
            if(assignmentOpIndex == -1) {
                // break up into operations
                val grouped = flat.groupBy {
                    if(it is Modification.OnField<*, *>) it.key
                    else null
                }.mapValues {
                    if(it.key == null) it.value.toMod()
                    else Modification.OnField(it.key as SerializableProperty<T, Any?>,
                        it.value.map { (it as Modification.OnField<T, Any?>).modification }.toMod().simplify()
                    )
                }
                grouped.values.toList().toMod()
            } else {
                // fold into single assignment
                var value = (flat[assignmentOpIndex] as Modification.Assign).value
                for (mod in flat.subList(assignmentOpIndex + 1, flat.size)) {
                    value = mod(value)
                }
                Modification.Assign(value)
            }
        }
        is Modification.OnField<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            Modification.OnField(
                this.key as SerializableProperty<T, Any?>,
                this.modification.simplify() as Modification<Any?>
            )
        }
        is Modification.ListPerElement<*> -> {
            @Suppress("UNCHECKED_CAST")
            Modification.ListPerElement(
                condition = condition.simplify(),
                modification = (modification as Modification<Nothing>).simplify()
            ) as Modification<T>
        }
        is Modification.SetPerElement<*> -> {
            @Suppress("UNCHECKED_CAST")
            Modification.SetPerElement(
                condition = condition.simplify(),
                modification = (modification as Modification<Nothing>).simplify()
            ) as Modification<T>
        }
        is Modification.ModifyByKey<*> -> {
            @Suppress("UNCHECKED_CAST")
            Modification.ModifyByKey(
                map = map.mapValues { it.value.simplify() }
            ) as Modification<T>
        }
        else -> this
    }
}

// at each layer, group by field and chain internally
//