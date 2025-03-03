package com.lightningkite.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer

interface SerializableProperty<A, B> {
    val name: String
    fun get(receiver: A): B
    fun setCopy(receiver: A, value: B): A
    val serializer: KSerializer<B>
    val annotations: List<Annotation> get() = listOf()
    val default: B? get() = null
    val defaultCode: String? get() = null
    val serializableAnnotations: List<SerializableAnnotation> get() = annotations.mapNotNull { SerializableAnnotation.parseOrNull(it) }

    companion object {
    }

    class FromVirtualField(val source: VirtualField, val registry: SerializationRegistry, val context: Map<String, KSerializer<*>>): SerializableProperty<VirtualInstance, Any?> {
        override val name: String get() = source.name
        override val serializer: KSerializer<Any?> by lazy { source.type.serializer(registry, context) }
        override val annotations: List<Annotation> get() = listOf()
        override val serializableAnnotations: List<SerializableAnnotation> get() = source.annotations
        override fun get(receiver: VirtualInstance): Any? = receiver.values[source.index]
        override fun setCopy(receiver: VirtualInstance, value: Any?): VirtualInstance = VirtualInstance(receiver.type, receiver.values.toMutableList().also {
            it[source.index] = value
        })
    }
}
@OptIn(ExperimentalSerializationApi::class)
fun <T> KSerializer<T>.tryFindAnnotations(propertyName: String): List<Annotation> {
    val i = descriptor.getElementIndex(propertyName)
    if (i < 0) return listOf()
    else return descriptor.getElementAnnotations(i)
}
private val serClassToList = HashMap<String, (Array<KSerializer<*>>)->Array<SerializableProperty<*, *>>>()

@OptIn(ExperimentalSerializationApi::class)
@Suppress("UNCHECKED_CAST")
val <T> KSerializer<T>.serializableProperties: Array<SerializableProperty<T, *>>? get() = (serClassToList[this.descriptor.serialName]?.invoke(tryTypeParameterSerializers() ?: arrayOf())) as? Array<SerializableProperty<T, *>>
    ?: (this as? VirtualStruct.Concrete)?.serializableProperties as? Array<SerializableProperty<T, *>>

@OptIn(ExperimentalSerializationApi::class)
fun <T, S: KSerializer<T>> S.properties(action: (Array<KSerializer<Nothing>>)->Array<SerializableProperty<T, *>>) {
    @Suppress("UNCHECKED_CAST")
    serClassToList[descriptor.serialName] = action as (Array<KSerializer<*>>)->Array<SerializableProperty<*, *>>
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
