@file:SharedCode

package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.*
import com.lightningkite.lightningdb.SerializableProperty
import kotlinx.serialization.KSerializer

inline fun <reified T : IsCodableAndHashable> sort(setup: SortBuilder<T>.(DataClassPath<T, T>) -> Unit): List<SortPart<T>> {
    return SortBuilder<T>().apply {
        setup(this, path())
    }.build()
}

class SortBuilder<K : IsCodableAndHashable>() {
    val sortParts = ArrayList<SortPart<K>>()
    fun add(sort: SortPart<K>) { sortParts.add(sort) }
    fun build(): List<SortPart<K>> = sortParts.toList()
    @JsName("ascending") fun <V> DataClassPath<K, V>.ascending() = add(SortPart<K>(this, true))
    @JsName("descending") fun <V> DataClassPath<K, V>.descending() = add(SortPart<K>(this, false))
    @JsName("ascendingString") fun DataClassPath<K, String>.ascending(ignoreCase: Boolean) = add(SortPart<K>(this, true, ignoreCase))
    @JsName("descendingString") fun DataClassPath<K, String>.descending(ignoreCase: Boolean) = add(SortPart<K>(this, false, ignoreCase))
}