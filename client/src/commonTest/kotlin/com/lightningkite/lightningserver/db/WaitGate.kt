package com.lightningkite.lightningserver.db

import com.lightningkite.kiteui.CancelledException
import com.lightningkite.kiteui.suspendCoroutineCancellable
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WaitGate(permit: Boolean = false) {
    var permit: Boolean = permit
        set(value) {
            field = value
            if (value) {
                for (continuation in continuations) {
                    continuation.resume(Unit)
                }
                continuations.clear()
            }
        }
    val continuations = ArrayList<Continuation<Unit>>()
    suspend fun await(): Unit {
        if (permit) return
        else return suspendCoroutineCancellable {
            continuations.add(it)
            return@suspendCoroutineCancellable {}
        }
    }
    fun abandon() {
        for (continuation in continuations) {
            continuation.resumeWithException(CancelledException())
        }
        continuations.clear()
    }
}