package com.lightningkite.lightningserver.typed

val Documentable.primaryAuthName: String?
    get() = authOptions.find { it != null }?.type?.authName