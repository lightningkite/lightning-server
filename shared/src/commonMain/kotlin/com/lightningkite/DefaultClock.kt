package com.lightningkite

import kotlinx.datetime.Clock

private var DefaultClock: Clock = Clock.System
var Clock.Companion.default: Clock
    get() = DefaultClock
    set(value) { DefaultClock = value }
@Suppress("NOTHING_TO_INLINE")
inline fun now() = Clock.default.now()