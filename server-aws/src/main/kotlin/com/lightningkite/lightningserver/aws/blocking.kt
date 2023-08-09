package com.lightningkite.lightningserver.aws

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.runBlocking
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit

fun <T> blockingTimeout(timeoutMs: Long, action: suspend ()->T): T {
    val result = GlobalScope.async(Dispatchers.Default) {
        action()
    }
    return result.asCompletableFuture().get(timeoutMs, TimeUnit.MILLISECONDS)
}