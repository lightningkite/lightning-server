package com.lightningkite.lightningdb

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer
import java.util.*

interface KProperty1Alt<A, B> {
    val name: String
    fun get(receiver: A): B
    fun setCopy(receiver: A, value: B): A
    val serializer: KSerializer<B>
}

//object UUID_mostSignificantBits: KProperty1Alt<UUID, Long> {
//    override val name: String = "mostSignificantBits"
//    override fun get(receiver: UUID): Long = receiver.mostSignificantBits
//    override fun setCopy(receiver: UUID, value: Long): UUID = UUID(value, receiver.leastSignificantBits)
//    override val serializer: KSerializer<Long> = Long.serializer()
//}
//
//inline fun <reified T> List_first() = List_first(serializer<T>())
//data class List_first<T>(val t: KSerializer<T>): KProperty1Alt<List<T>, T> {
//    override val name: String = "first"
//    override val serializer: KSerializer<T> = t
//    override fun get(receiver: List<T>): T = receiver.first()
//    override fun setCopy(receiver: List<T>, value: T): List<T> = listOf(value) + receiver.drop(1)
//}