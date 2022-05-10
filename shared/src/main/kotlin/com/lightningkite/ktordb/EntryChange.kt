@file:SharedCode
package com.lightningkite.ktordb

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.SharedCode
import kotlinx.serialization.Serializable

@Serializable
data class EntryChange<T: IsCodableAndHashable>(
    val old: T? = null,
    val new: T? = null
)

inline fun <T: IsCodableAndHashable, B: IsCodableAndHashable> EntryChange<T>.map(mapper: (T)->B): EntryChange<B> {
    return EntryChange(old?.let(mapper), new?.let(mapper))
}