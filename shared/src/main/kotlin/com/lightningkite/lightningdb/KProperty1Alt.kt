package com.lightningkite.lightningdb

import kotlinx.serialization.KSerializer

interface KProperty1Alt<A, B> {
    val name: String
    fun get(receiver: A): B
    fun setCopy(receiver: A, value: B): A
    val serializer: KSerializer<B>
}