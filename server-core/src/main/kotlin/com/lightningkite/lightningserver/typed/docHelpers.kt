package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.auth.noAuth
import kotlin.reflect.KClass

val Documentable.primaryAuthName: String?
    get() = if(authOptions == noAuth) null else authOptions.options.find { it != null }?.type?.let { it.authName ?: (it.classifier as? KClass<*>)?.simpleName }