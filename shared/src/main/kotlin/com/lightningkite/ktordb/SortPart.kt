@file:SharedCode
package com.lightningkite.ktordb

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.SharedCode
import kotlinx.serialization.Serializable
import kotlin.reflect.KProperty1

@Serializable(SortPartSerializer::class)
@Description("The name of the property to sort by.  Prepend a '-' if you wish to sort descending.")
data class SortPart<T: IsCodableAndHashable>(
    val field: KProperty1Partial<T>,
    val ascending: Boolean = true
) {
    constructor(field: KProperty1<T, *>, ascending: Boolean = true):this(KProperty1Partial(field), ascending)
}

val <T: IsCodableAndHashable> List<SortPart<T>>.comparator: Comparator<T>? get() {
    if(this.isEmpty()) return null
    return Comparator { a, b ->
        for(part in this) {
            val result = part.field.compare.compare(a, b)
            if(result != 0) return@Comparator if(part.ascending) result else -result
        }
        return@Comparator 0
    }
}