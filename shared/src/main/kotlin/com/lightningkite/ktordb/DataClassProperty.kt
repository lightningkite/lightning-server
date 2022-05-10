@file:SharedCode
package com.lightningkite.ktordb

import com.lightningkite.khrysalis.SharedCode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

abstract class PartialDataClassProperty<K> {
    abstract val name: String
    abstract fun anyGet(k: K): Any?
    abstract fun anySet(k: K, v: Any?): K
    abstract val compare: Comparator<K>?

    override fun hashCode(): Int = name.hashCode()
    @Suppress("UNCHECKED_CAST")
    override fun equals(other: Any?): Boolean = (other as? PartialDataClassProperty<K>)?.name == this.name
    override fun toString(): String = name
}
class DataClassProperty<K, V>(
    override val name: String,
    get: (K)->V,
    set: (K, V)->K,
    override val compare: Comparator<K>? = null
): PartialDataClassProperty<K>() {
    private val getter: (K)->V = get
    private val setter: (K, V)->K = set
    operator fun get(on: K): V = getter(on)
    operator fun set(on: K, value: V): K = setter(on, value)

    override fun anyGet(k: K): Any? = getter(k)
    @Suppress("UNCHECKED_CAST")
    override fun anySet(k: K, v: Any?) = setter(k, v as V)
}