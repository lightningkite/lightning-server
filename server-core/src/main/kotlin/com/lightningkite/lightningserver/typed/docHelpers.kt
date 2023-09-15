package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.auth.noAuth

val Documentable.primaryAuthName: String?
    get() = if(authOptions == noAuth) null else authOptions.options.find { it != null }?.type?.authName