package com.lightningkite.lightningserver.aws

import kotlinx.coroutines.*
import kotlinx.coroutines.future.asCompletableFuture
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit

@OptIn(DelicateCoroutinesApi::class)
fun <T> blockingTimeout(timeoutMs: Long, action: suspend ()->T): T {
    val result = GlobalScope.async(Dispatchers.Default) {
        action()
    }
    return result.asCompletableFuture().get(timeoutMs, TimeUnit.MILLISECONDS)
}