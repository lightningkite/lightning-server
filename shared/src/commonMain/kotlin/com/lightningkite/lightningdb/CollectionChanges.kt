@file:SharedCode
package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.Equatable
import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.JsName
import com.lightningkite.khrysalis.SharedCode
import kotlinx.serialization.Serializable


@Serializable
data class CollectionChanges<T: IsCodableAndHashable>(
    val changes: List<EntryChange<T>> = listOf()
) {
    @JsName("pair")
    constructor(old: T? = null, new: T? = null):this(changes = if(old != null || new != null) listOf(EntryChange(old, new)) else listOf())
}

fun <T: HasId<ID>, ID: Comparable<ID>> List<T>.apply(changes: CollectionChanges<T>): List<T> {
    val changeable = this.toMutableList()
    for(change in changes.changes) {
        change.old?.let { old -> changeable.removeAll { it._id == old._id }}
        change.new?.let { new -> changeable.add(new)}
    }
    return changeable
}

// This will not convert well. Manually add the type argument to the return EntryChange on the swift side. "EntryChange<B>"
inline fun <T: IsCodableAndHashable, B: IsCodableAndHashable> CollectionChanges<T>.map(mapper: (T)->B): CollectionChanges<B> {
    return CollectionChanges<B>(changes = changes.map { it.map(mapper) })
}