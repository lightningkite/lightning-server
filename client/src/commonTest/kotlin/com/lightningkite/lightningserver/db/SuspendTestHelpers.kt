package com.lightningkite.lightningserver.db

import com.lightningkite.kiteui.Cancellable
import com.lightningkite.kiteui.reactive.CalculationContext
import com.lightningkite.kiteui.reactive.CalculationContextStack
import com.lightningkite.kiteui.suspendCoroutineCancellable
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.test.assertEquals


class VirtualDelay<T>(val action: () -> T) {
    val continuations = ArrayList<Continuation<T>>()
    var value: T? = null
    var ready: Boolean = false
    suspend fun await(): T {
        if(ready) return value as T
        return suspendCoroutineCancellable {
            continuations.add(it)
            return@suspendCoroutineCancellable {}
        }
    }
    fun clear() {
        ready = false
    }
    fun go() {
        val value = action()
        this.value = value
        ready = true
        for(continuation in continuations) {
            continuation.resume(value)
        }
        continuations.clear()
    }
}

class VirtualDelayer() {
    val continuations = ArrayList<Continuation<Unit>>()
    suspend fun await(): Unit {
        return suspendCoroutineCancellable {
            continuations.add(it)
            return@suspendCoroutineCancellable {}
        }
    }
    fun go() {
        for(continuation in continuations) {
            continuation.resume(Unit)
        }
        continuations.clear()
    }
}

fun testContext(action: CalculationContext.()->Unit): Cancellable {
    var error: Throwable? = null
    val onRemoveSet = HashSet<()->Unit>()
    var numOutstandingContracts = 0
    with(object: CalculationContext {
        override fun onRemove(action: () -> Unit) {
            onRemoveSet.add(action)
        }

        override fun notifyLongComplete(result: Result<Unit>) {
            numOutstandingContracts--
        }

        override fun notifyStart() {
            numOutstandingContracts++
        }

        override fun notifyComplete(result: Result<Unit>) {
            result.onFailure { t ->
                t.printStackTrace()
                error = t
            }
        }
    }) {
        CalculationContextStack.useIn(this) {
            action()
        }
        if(error != null) throw error!!
        assertEquals(numOutstandingContracts, 0)
    }
    return object: Cancellable {
        override fun cancel() {
            onRemoveSet.forEach { it() }
            onRemoveSet.clear()
        }
    }
}