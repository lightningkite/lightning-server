package com.lightningkite.serialization

val <T> DataClassPathPartial<T>.compare: Comparator<T> get() = compareBy { this.getAny(it) as? Comparable<*> }
