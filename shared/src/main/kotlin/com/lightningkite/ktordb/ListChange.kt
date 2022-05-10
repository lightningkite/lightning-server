@file:SharedCode
package com.lightningkite.ktordb

import com.lightningkite.khrysalis.IsCodableAndHashable
import com.lightningkite.khrysalis.SharedCode
import kotlinx.serialization.Serializable

@Serializable
data class ListChange<T: IsCodableAndHashable>(
    val wholeList: List<T>? = null,
    val old: T? = null,
    val new: T? = null
)

fun <T: IsCodableAndHashable> EntryChange<T>.listChange() = ListChange(
    old = old,
    new = new,
)