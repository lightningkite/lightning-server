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
            val p = DataClassPathAccess(soFar, part.key as SerializableProperty<T, Any?>)
            if(part.value is Partial<*> && part.key.serializer.let { it.nullElement() ?: it } !is PartialSerializer<*>) {
                (part.value as Partial<Any?>).perPath(p, action)
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
class PartialBuilder<T>(val parts: MutableMap<SerializableProperty<T, *>, Any?> = mutableMapOf()) {
    inline operator fun <A> DataClassPath<T, A>.invoke(setup: PartialBuilder<A>.(DataClassPathSelf<A>) -> Unit) {
        if(this !is DataClassPathAccess<*, *, *>) throw IllegalArgumentException()
        parts[this.second as SerializableProperty<T, *>] = PartialBuilder<A>().apply { setup(DataClassPathSelf(second.serializer as KSerializer<A>)) }.let { Partial<A>(it.parts) }
    }
    inline fun <A: Any> DataClassPath<T, A?>.notNull(setup: PartialBuilder<A>.(DataClassPathSelf<A>) -> Unit) {
        if(this !is DataClassPathAccess<*, *, *>) throw IllegalArgumentException()
        parts[this.second as SerializableProperty<T, *>] = PartialBuilder<A>().apply { setup(DataClassPathSelf(second.serializer as KSerializer<A>)) }.let { Partial<A>(it.parts) }
    }

    @Suppress("NOTHING_TO_INLINE")
    inline infix fun <A> DataClassPath<T, A>.assign(value: Partial<A>) {
        if(this !is DataClassPathAccess<*, *, *>) throw IllegalArgumentException()
        parts[this.second as SerializableProperty<T, *>] = value
    }

    @Suppress("NOTHING_TO_INLINE")
    inline infix fun <A> DataClassPath<T, A>.assign(value: A) {
        if(this !is DataClassPathAccess<*, *, *>) throw IllegalArgumentException()
        this.second.serializer.serializableProperties?.let {
            parts[this.second as SerializableProperty<T, *>] = partialOf(value, it as Array<SerializableProperty<Any?, *>>)
        } ?: run {
            parts[this.second as SerializableProperty<T, *>] = value
        }
    }
}

inline fun <reified T> partialOf(builder: PartialBuilder<T>.(DataClassPathSelf<T>) -> Unit): Partial<T> =
    PartialBuilder<T>().apply {
        builder(
            DataClassPathSelf(
                serializer()
            )
        )
    }.parts.let { Partial(it) }


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