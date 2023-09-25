package com.lightningkite.lightningdb

import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

interface SerializableProperty<A, B> {
    val name: String
    fun get(receiver: A): B
    fun setCopy(receiver: A, value: B): A
    val serializer: KSerializer<B>

    companion object {
    }
}
private val serClassToList = HashMap<KClass<*>, (Array<KSerializer<*>>)->Array<SerializableProperty<*, *>>>()
@Suppress("UNCHECKED_CAST")
val <T> KSerializer<T>.serializableProperties: Array<SerializableProperty<T, *>> get() = (serClassToList[this::class]?.invoke(tryTypeParameterSerializers() ?: arrayOf()) ?: arrayOf()) as Array<SerializableProperty<T, *>>
fun <T, S: KSerializer<T>> S.properties(action: (Array<KSerializer<Nothing>>)->Array<SerializableProperty<T, *>>) {
    @Suppress("UNCHECKED_CAST")
    serClassToList[this::class] = action as (Array<KSerializer<*>>)->Array<SerializableProperty<*, *>>
}

//object UUID_mostSignificantBits: SerializableProperty<UUID, Long> {
//    override val name: String = "mostSignificantBits"
//    override fun get(receiver: UUID): Long = receiver.mostSignificantBits
//    override fun setCopy(receiver: UUID, value: Long): UUID = UUID(value, receiver.leastSignificantBits)
//    override val serializer: KSerializer<Long> = Long.serializer()
//}
//
//inline fun <reified T> List_first() = List_first(serializer<T>())
//data class List_first<T>(val t: KSerializer<T>): SerializableProperty<List<T>, T> {
//    override val name: String = "first"
//    override val serializer: KSerializer<T> = t
//    override fun get(receiver: List<T>): T = receiver.first()
//    override fun setCopy(receiver: List<T>, value: T): List<T> = listOf(value) + receiver.drop(1)
//}