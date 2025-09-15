package com.lightningkite.lightningserver.typed

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
public annotation class LiveVersion(val live: KClass<*>)