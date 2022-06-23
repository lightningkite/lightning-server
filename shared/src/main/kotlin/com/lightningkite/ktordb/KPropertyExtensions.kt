package com.lightningkite.ktordb

import com.lightningkite.khrysalis.SharedCode
import kotlin.reflect.KProperty1

val KProperty1_setCopyImplementation = HashMap<KProperty1<*, *>, (Any, Any?)->Any>()
fun <T: Any, V> KProperty1<T, V>.setCopy(original: T, value: V): V {
    @Suppress("UNCHECKED_CAST")
    return KProperty1_setCopyImplementation[this]!!(original, value) as V
}
typealias KProperty1Partial<T> = KProperty1<T, *>

@Deprecated("Reference KProperty instead", ReplaceWith("KProperty1Partial<T>"))
typealias PartialDataClassProperty<T> = KProperty1Partial<T>
@Deprecated("Reference KProperty instead", ReplaceWith("KProperty1<T, V>", "kotlin.reflect.KProperty1"))
typealias DataClassProperty<T, V> = KProperty1<T, V>