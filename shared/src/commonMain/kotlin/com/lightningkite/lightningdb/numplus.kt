package com.lightningkite.lightningdb


@Suppress("UNCHECKED_CAST")
internal operator fun <T : Number> T.plus(other: T): T {
    return when (this) {
        is Byte -> (this + other as Byte) as T
        is Short -> (this + other as Short) as T
        is Int -> (this + other as Int) as T
        is Long -> (this + other as Long) as T
        is Float -> (this + other as Float) as T
        is Double -> (this + other as Double) as T
        else -> throw IllegalArgumentException()
    }
}

@Suppress("UNCHECKED_CAST")
internal operator fun <T : Number> T.times(other: T): T {
    return when (this) {
        is Byte -> (this * other as Byte) as T
        is Short -> (this * other as Short) as T
        is Int -> (this * other as Int) as T
        is Long -> (this * other as Long) as T
        is Float -> (this * other as Float) as T
        is Double -> (this * other as Double) as T
        else -> throw IllegalArgumentException()
    }
}