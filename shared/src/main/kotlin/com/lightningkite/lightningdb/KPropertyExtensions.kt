package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.lightningdb.SerializableProperty

private val SerializableProperty_setCopyImplementation = HashMap<SerializableProperty<*, *>, (Any?, Any?)->Any?>()
fun <T, V> SerializableProperty<T, V>.setCopyImplementation(impl: (original: T, value: V) -> T) {
    @Suppress("UNCHECKED_CAST")
    SerializableProperty_setCopyImplementation[this] = impl as (Any?, Any?)->Any?
}
fun <T, V> SerializableProperty<T, V>.setCopy(original: T, value: V): T {
    @Suppress("UNCHECKED_CAST")
    val impl = SerializableProperty_setCopyImplementation[this] ?: throw Error("setCopy implementation not present for $this.  Did you forget to call prepareModels()?")
    return impl(original, value) as T
}

val <T> DataClassPathPartial<T>.compare: Comparator<T> get() = compareBy { this.getAny(it) as? Comparable<*> }
