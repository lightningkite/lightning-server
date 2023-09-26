package com.lightningkite.lightningdb

import com.lightningkite.lightningdb.SerializableProperty

data class Partial<T>(val parts: MutableMap<String, Any?> = mutableMapOf()) {
    constructor(item: T, paths: Set<DataClassPathPartial<T>>):this() {
        paths.forEach { it.setMap(item, this) }
    }
}

fun <T> partialOf(vararg parts: Pair<String, Any?>): Partial<T> = Partial<T>(mutableMapOf(*parts))