package com.lightningkite.lightningserver.concurrent

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

public suspend fun <T> Iterable<T>.forEachConcurrent(
    concurrent: Int,
    action: suspend (T) -> Unit
): Unit = coroutineScope {
    val permits = Semaphore(concurrent)

    for (item in this@forEachConcurrent) launch {
        permits.withPermit { action(item) }
    }
}

public suspend fun <T, R> Iterable<T>.mapConcurrent(
    concurrent: Int,
    action: suspend (T) -> R
): List<R> = coroutineScope {
    val permits = Semaphore(concurrent)

    map { item ->
        async { permits.withPermit { action(item) } }
    }.awaitAll()
}
