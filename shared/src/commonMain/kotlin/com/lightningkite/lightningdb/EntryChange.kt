@file:SharedCode
package com.lightningkite.lightningdb

import com.lightningkite.khrysalis.Equatable
import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.JsName
import com.lightningkite.khrysalis.SharedCode
import kotlinx.serialization.Serializable


@Serializable
data class EntryChange<T: IsCodableAndHashable>(
    val old: T? = null,
    val new: T? = null
) {
}

// This will not convert well. Manually add the type argument to the return EntryChange on the swift side. "EntryChange<B>"
inline fun <T: IsCodableAndHashable, B: IsCodableAndHashable> EntryChange<T>.map(mapper: (T)->B): EntryChange<B> {
    return EntryChange<B>(old?.let(mapper), new?.let(mapper))
}