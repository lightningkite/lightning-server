package com.lightningkite.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.serializer
import kotlin.jvm.JvmName

// TODO : Optimize for fully complete subobjects
// TODO : Better handle nullable objects for mod translation
@Serializable(PartialSerializer::class)
data class Partial<T>(
    val parts: MutableMap<SerializableProperty<T, *>, Any?> = mutableMapOf()
) {
    @Deprecated("Use partialOf instead.")
    constructor(item: T, paths: Iterable<DataClassPathPartial<T>>) : this() {
        paths.forEach { it.setMap(item, this) }
    }
    @Deprecated("Use partialOf instead.")
    constructor(item: T, paths: Array<DataClassPathPartial<T>>) : this() {
        paths.forEach { it.setMap(item, this) }
    }
    fun total(serializer: KSerializer<T>): T? = if(parts.keys.containsAll(serializer.serializableProperties!!.asList())) {
        var out = serializer.default()
        perPath(DataClassPathSelf(serializer)) {
            out = it.set(out)
        }
        out
    } else null
    fun <S> perPath(soFar: DataClassPath<S, T>, action: (DataClassPathWithValue<S, *>) -> Unit) {
        for(part in parts) {

            @Suppress("UNCHECKED_CAST")
            val p = DataClassPathAccess(soFar, part.key as SerializableProperty<T, Any?>)
            if(part.value is Partial<*> && part.key.serializer.let { it.nullElement() ?: it } !is PartialSerializer<*>) {

                @Suppress("UNCHECKED_CAST")
                val partial = (part.value as Partial<Any?>)
                val ser = part.key.serializer.let { it.nullElement() ?: it }

                @Suppress("UNCHECKED_CAST")
                partial.total(ser as KSerializer<Any?>)?.let {
                    action(DataClassPathWithValue(p, it))
                } ?: partial.perPath(p, action)
            } else {
                action(DataClassPathWithValue(p, part.value))
            }
        }
    }
}

data class DataClassPathWithValue<A, V>(val path: DataClassPath<A, V>, val value: V) {
    fun set(a: A): A = path.set(a, value)
}

@Suppress("UNCHECKED_CAST")
class PartialBuilder<T>(val partial: Partial<T> = Partial()) {
    inline infix fun <A> DataClassPath<T, A>.assign(value: A) {
        var target: Partial<Any> = partial as Partial<Any>
        val props = properties
        for (prop in props.dropLast(1)) {
            target = target.parts.getOrPut(prop as SerializableProperty<Any, Any>) { Partial<Any>() } as Partial<Any>
        }
        target.parts[props.last() as SerializableProperty<Any, A>] = value
    }
    inline infix fun <A> DataClassPath<T, A>.assign(value: Partial<A>) {
        var target: Partial<Any> = partial as Partial<Any>
        val props = properties
        for (prop in props.dropLast(1)) {
            target = target.parts.getOrPut(prop as SerializableProperty<Any, Any>) { Partial<Any>() } as Partial<Any>
        }
        target.parts[props.last() as SerializableProperty<Any, A>] = value
    }
}

inline fun <reified T> partialOf(builder: PartialBuilder<T>.(DataClassPathSelf<T>) -> Unit): Partial<T> =
    PartialBuilder<T>().apply {
        builder(
            DataClassPathSelf(
                serializer()
            )
        )
    }.partial


fun <T> partialOf(item: T, properties: Array<SerializableProperty<T, *>>) = Partial<T>().apply {
    properties.forEach { parts[it] = it.get(item) }
}
fun <T> partialOf(item: T, properties: Iterable<SerializableProperty<T, *>>) = Partial<T>().apply {
    properties.forEach { parts[it] = it.get(item) }
}
@JvmName("partialOfPaths")
fun <T> partialOf(item: T, paths: Iterable<DataClassPathPartial<T>>) = Partial<T>().apply {
    paths.forEach { it.setMap(item, this) }
}
@JvmName("partialOfPaths")
fun <T> partialOf(item: T, paths: Array<DataClassPathPartial<T>>) = Partial<T>().apply {
    paths.forEach { it.setMap(item, this) }
}