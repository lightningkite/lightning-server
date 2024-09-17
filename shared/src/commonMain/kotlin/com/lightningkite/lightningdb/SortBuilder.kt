package com.lightningkite.lightningdb

import com.lightningkite.serialization.DataClassPath
import kotlinx.serialization.KSerializer

inline fun <reified T> sort(setup: SortBuilder<T>.(DataClassPath<T, T>) -> Unit): List<SortPart<T>> {
    return SortBuilder<T>().apply {
        setup(this, path())
    }.build()
}

class SortBuilder<K>() {
    val sortParts = ArrayList<SortPart<K>>()
    fun add(sort: SortPart<K>) { sortParts.add(sort) }
    fun build(): List<SortPart<K>> = sortParts.toList()
    fun <V: Comparable<V>> DataClassPath<K, V>.ascending() = add(SortPart<K>(this, true))
    fun <V: Comparable<V>> DataClassPath<K, V>.descending() = add(SortPart<K>(this, false))
    fun DataClassPath<K, String>.ascending(ignoreCase: Boolean) = add(SortPart<K>(this, true, ignoreCase))
    fun DataClassPath<K, String>.descending(ignoreCase: Boolean) = add(SortPart<K>(this, false, ignoreCase))
}
