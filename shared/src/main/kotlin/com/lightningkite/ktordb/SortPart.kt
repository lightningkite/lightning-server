@file:SharedCode
package com.lightningkite.ktordb

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.SharedCode
import kotlinx.serialization.Serializable

@Serializable(SortPartSerializer::class)
data class SortPart<T: IsCodableAndHashable>(
    val field: PartialDataClassProperty<T>,
    val ascending: Boolean = true
)

val <T: IsCodableAndHashable> List<SortPart<T>>.comparator: Comparator<T>? get() {
    if(this.isEmpty()) return null
    return Comparator { a, b ->
        for(part in this) {
            val result = part.field.compare!!.compare(a, b)
            if(result != 0) return@Comparator if(part.ascending) result else -result
        }
        return@Comparator 0
    }
}