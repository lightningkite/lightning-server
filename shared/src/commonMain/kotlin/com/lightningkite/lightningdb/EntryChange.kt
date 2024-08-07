
package com.lightningkite.lightningdb


import kotlinx.serialization.Serializable


@Serializable
data class EntryChange<T>(
    val old: T? = null,
    val new: T? = null
) {
}

// This will not convert well. Manually add the type argument to the return EntryChange on the swift side. "EntryChange<B>"
inline fun <T, B> EntryChange<T>.map(mapper: (T)->B): EntryChange<B> {
    return EntryChange<B>(old?.let(mapper), new?.let(mapper))
}