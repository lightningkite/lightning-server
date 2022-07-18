package com.lightningkite.lightningserver.core

import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.websocket.*
import com.lightningkite.lightningserver.schedule.*
import com.lightningkite.lightningserver.typed.typed
import java.time.Duration
import java.util.*

@DslMarker
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
annotation class LightningServerDsl

@LightningServerDsl inline fun routing(action: ServerPath.() -> Unit) = ServerPath.root.apply(action)
