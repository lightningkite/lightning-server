package com.lightningkite.ktordb

import com.lightningkite.khrysalis.SharedCode
import kotlin.reflect.KProperty1

private val KProperty1_setCopyImplementation = HashMap<KProperty1<*, *>, (Any?, Any?)->Any?>()
fun <T, V> KProperty1<T, V>.setCopyImplementation(impl: (original: T, value: V) -> T) {
    @Suppress("UNCHECKED_CAST")
    KProperty1_setCopyImplementation[this] = impl as (Any?, Any?)->Any?
}
fun <T, V> KProperty1<T, V>.setCopy(original: T, value: V): T {
    @Suppress("UNCHECKED_CAST")
    return KProperty1_setCopyImplementation[this]!!(original, value) as T
}

@kotlinx.serialization.Serializable(KPropertyPartialSerializer::class)
data class KProperty1Partial<T>(val property: KProperty1<T, *>) {
    val compare: Comparator<T> = compareBy { property.get(it) as? Comparable<*> }
}

@Deprecated("Reference KProperty instead", ReplaceWith("KProperty1Partial<T>"))
typealias PartialDataClassProperty<T> = KProperty1Partial<T>
@Deprecated("Reference KProperty instead", ReplaceWith("KProperty1<T, V>", "kotlin.reflect.KProperty1"))
typealias DataClassProperty<T, V> = KProperty1<T, V>