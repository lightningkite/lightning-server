@file:SharedCode

package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.*
import kotlin.reflect.KProperty1

inline fun <T : IsCodableAndHashable> sort(setup: SortBuilder<T>.(DataClassPath<T, T>) -> Unit): List<SortPart<T>> {
    return SortBuilder<T>().apply {
        setup(path())
    }.build()
}

class SortBuilder<K : IsCodableAndHashable>() {
    val sortParts = ArrayList<SortPart<K>>()
    fun add(sort: SortPart<K>) = sortParts.add(sort)
    fun build(): List<SortPart<K>> = sortParts.toList()
    fun <V> DataClassPath<K, V>.ascending() = SortPart(this, true)
    fun <V> DataClassPath<K, V>.descending() = SortPart(this, false)
    fun DataClassPath<K, String>.ascending(ignoreCase: Boolean) = SortPart(this, true, ignoreCase)
    fun DataClassPath<K, String>.descending(ignoreCase: Boolean) = SortPart(this, false, ignoreCase)
}