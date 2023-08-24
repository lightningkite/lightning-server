package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.HasId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async

data class LazyModel<T, ID>(
    val id: ID,
    val fetcher: suspend (ID)->T
) {
    val value = GlobalScope.async(start = CoroutineStart.LAZY) { fetcher(id) }
}