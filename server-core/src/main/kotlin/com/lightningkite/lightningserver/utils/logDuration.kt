package com.lightningkite.lightningserver.utils

import kotlin.time.measureTime

inline fun <R> logDuration(label: String, action: ()->R): R {
    var r: R
    measureTime {
        r = action()
    }.also { println("$label took $it") }
    return r
}