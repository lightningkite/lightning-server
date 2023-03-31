package com.lightningkite.lightningserver.core

@DslMarker
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
annotation class LightningServerDsl

@LightningServerDsl
inline fun routing(action: ServerPath.() -> Unit) = ServerPath.root.apply(action)
