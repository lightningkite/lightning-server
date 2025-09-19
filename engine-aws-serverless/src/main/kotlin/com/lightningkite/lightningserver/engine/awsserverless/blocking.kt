package com.lightningkite.lightningserver.engine.awsserverless

import kotlinx.coroutines.*
import kotlinx.coroutines.future.asCompletableFuture
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit

@OptIn(DelicateCoroutinesApi::class)
internal fun <T> blockingTimeout(timeoutMs: Long, action: suspend ()->T): T {
    val result = GlobalScope.async(Dispatchers.Default) {
        action()
    }
    return result.asCompletableFuture().get(timeoutMs, TimeUnit.MILLISECONDS)
}

internal suspend fun <T> Iterable<T>.forEachConcurrent(action: suspend (T)->Unit) {
    coroutineScope {
        map { async(Dispatchers.IO) { action(it) } }.awaitAll()
    }
}