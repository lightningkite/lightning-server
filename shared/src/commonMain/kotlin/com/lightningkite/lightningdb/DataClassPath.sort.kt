package com.lightningkite.lightningdb

val <T> DataClassPathPartial<T>.compare: Comparator<T> get() = compareBy { this.getAny(it) as? Comparable<*> }
