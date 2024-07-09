package com.lightningkite.lightningserver.db

import com.lightningkite.default
import com.lightningkite.kiteui.launchGlobal
import com.lightningkite.kiteui.printStackTrace2
import com.lightningkite.lightningdb.condition
import com.lightningkite.lightningdb.eq
import com.lightningkite.lightningdb.lte
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.coroutines.*
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class VirtualClock : Clock, CoroutineContext.Element {
    override val key: CoroutineContext.Key<*>
        get() = Key
    object Key: CoroutineContext.Key<VirtualClock>
    val start = Instant.fromEpochMilliseconds(0)
    var time = start
        private set
    val seconds get() = (time - start).inWholeSeconds
    var queued = listOf<Pair<Instant, Continuation<Unit>>>()
    operator fun plusAssign(duration: Duration) {
        time += duration
        val toRun = queued.filter { now() > it.first }
        val toKeep = queued.filter { !(now() > it.first) }
        queued = toKeep
        toRun.forEach { it.second.resume(Unit) }
    }

    override fun now(): Instant = time
}

inline fun suspendTest(crossinline test: suspend ()->Unit) {
    var f: Throwable? = null
    var complete = false
    launchGlobal {
        try {
            test()
            complete = true
        } catch(e: Throwable) {
            f = e
            e.printStackTrace2()
        }
    }
    if(!complete) fail("Test did not complete")
    f?.let {
        fail(it.message)
    }
}

fun timeTravelTest(action: (VirtualClock)->Unit) {
    val oldClock = Clock.default

    try {
        val virtualClock = VirtualClock()
        Clock.default = virtualClock

        action(virtualClock)
    } finally {
        Clock.default = oldClock
    }
}

inline fun repeatWhile(cap: Int = 100, condition: ()->Boolean, action: ()->Unit) {
    var i = 0;
    while(i < cap) {
        if(!condition()) break
        action()
        i++
    }
}