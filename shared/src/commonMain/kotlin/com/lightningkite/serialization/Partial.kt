package com.lightningkite.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

@Serializable(PartialSerializer::class)
data class Partial<T>(val parts: MutableMap<SerializableProperty<T, *>, Any?> = mutableMapOf()) {
    constructor(item: T, paths: Set<DataClassPathPartial<T>>) : this() {
        paths.forEach { it.setMap(item, this) }
    }
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
        parts[this.second as SerializableProperty<T, *>] = value
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