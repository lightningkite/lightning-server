package com.lightningkite.lightningserver.audit

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Runs [action] on every element, at most [maxConcurrent] at a time, and returns the results in order. */
internal suspend fun <T, R> Iterable<T>.mapConcurrently(
    maxConcurrent: Int,
    action: suspend (T) -> R,
): List<R> = coroutineScope {
    val permits = Semaphore(maxConcurrent)
    map { async { permits.withPermit { action(it) } } }.awaitAll()
}

/** Runs [action] on every element, at most [maxConcurrent] at a time. */
internal suspend fun <T> Iterable<T>.forEachConcurrently(
    maxConcurrent: Int,
    action: suspend (T) -> Unit,
): Unit = coroutineScope {
    val permits = Semaphore(maxConcurrent)
    forEach { launch { permits.withPermit { action(it) } } }
}
