package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.SharedCode
import kotlin.reflect.KProperty1

private val KProperty1_setCopyImplementation = HashMap<KProperty1<*, *>, (Any?, Any?)->Any?>()
fun <T, V> KProperty1<T, V>.setCopyImplementation(impl: (original: T, value: V) -> T) {
    @Suppress("UNCHECKED_CAST")
    KProperty1_setCopyImplementation[this] = impl as (Any?, Any?)->Any?
}
fun <T, V> KProperty1<T, V>.setCopy(original: T, value: V): T {
    @Suppress("UNCHECKED_CAST")
    val impl = KProperty1_setCopyImplementation[this] ?: throw Error("setCopy implementation not present for $this.  Did you forget to call prepareModels()?")
    return impl(original, value) as T
}

@Deprecated("Don't use this!", ReplaceWith("DataClassPathPartial<T>"))
typealias KProperty1Partial<T> = DataClassPathPartial<T>
val <T> DataClassPathPartial<T>.compare: Comparator<T> get() = compareBy { this.getAny(it) as? Comparable<*> }
