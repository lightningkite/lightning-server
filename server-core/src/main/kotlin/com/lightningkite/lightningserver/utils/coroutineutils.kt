package com.lightningkite.lightningserver.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.cancellation.CancellationException

suspend fun cancellingScope(action: suspend CoroutineScope.() -> Unit) {
    try {
        coroutineScope {
            action()
            cancel("Normal completion, cancelling to terminate children")
        }
    } catch(e: CancellationException) {
        // OK
    }
}