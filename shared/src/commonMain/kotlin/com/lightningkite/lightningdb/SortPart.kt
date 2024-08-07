package com.lightningkite.lightningdb


import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlin.reflect.KProperty1

@Serializable(SortPartSerializer::class)
@Description("The name of the property to sort by.  Prepend a '-' if you wish to sort descending.")
data class SortPart<T>(
    val field: DataClassPathPartial<T>,
    val ascending: Boolean = true,
    val ignoreCase: Boolean = false
)

@OptIn(ExperimentalSerializationApi::class)
val <T> List<SortPart<T>>.comparator: Comparator<T>? get() {
    if(this.isEmpty()) return null
    return Comparator { a, b ->
        for(part in this) {
            if (part.ignoreCase && part.field.serializerAny.descriptor.kind == PrimitiveKind.STRING) {
                val aString = part.field.getAny(a) as String
                val bString = part.field.getAny(b) as String
                val result = aString.compareTo(bString, true)
                if(result != 0) return@Comparator if(part.ascending) result else -result
            } else {
                val result = part.field.compare.compare(a, b)
                if(result != 0) return@Comparator if(part.ascending) result else -result
            }
        }
        return@Comparator 0
    }
}