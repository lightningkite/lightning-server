package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.definition.builder.ListRegistry
import com.lightningkite.lightningserver.definition.builder.Registry
import kotlin.properties.ReadOnlyProperty
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

public operator fun <T : Any> Extensions.Key<T>.getValue(thisRef: Extended, property: KProperty<*>): T? =
    thisRef.extensions[this]

public operator fun <T : Any> MutableExtensions.Key<T>.setValue(thisRef: Extendable, property: KProperty<*>, value: T?) {
    thisRef.extensions[this] = value
}

// defaults
public fun <T : Any> MutableExtensions.getOrPut(key: MutableExtensions.Key<T>, default: () -> T): T = get(key) ?: default().also { set(key, it) }

public fun <T : Any, E : Extendable> MutableExtensions.Key<T>.cache(default: E.() -> T): ReadWriteProperty<E, T> =
    object : ReadWriteProperty<E, T> {
        override fun getValue(thisRef: E, property: KProperty<*>): T = thisRef.extensions.getOrPut(this@cache) { default(thisRef) }
        override fun setValue(thisRef: E, property: KProperty<*>, value: T) { thisRef.extensions[this@cache] = value }
    }

public fun <T : Any, E : Extended> Extensions.Key<T>.default(default: E.() -> T): ReadOnlyProperty<E, T> =
    ReadOnlyProperty { thisRef: E, _ -> thisRef.extensions[this] ?: default(thisRef) }

// degrading
public operator fun <M : T, T : Any> MutableExtensions.DegradingKey<M, T>.getValue(thisRef: Extendable, property: KProperty<*>): M =
    thisRef.extensions[this]

public operator fun <M : T, T : Any> MutableExtensions.DegradingKey<M, T>.getValue(thisRef: Extended, property: KProperty<*>): T =
    thisRef.extensions[this] ?: default()


/**
 * A convenience wrapper of [MutableExtensions.DegradingKey] for a [Registry], providing an implementation of
 * [default].
 * */
public interface RegistryExtension<L, V : Any> : MutableExtensions.DegradingKey<Registry<L, V>, Map<L, V>> {
    override fun default(): Registry<L, V> = Registry()
}

/**
 * A convenience wrapper of [MutableExtensions.DegradingKey] for a [ListRegistry], providing an implementation of
 * [default].
 * */
public interface ListRegistryExtension<V> : MutableExtensions.DegradingKey<ListRegistry<V>, List<V>> {
    override fun default(): ListRegistry<V> = ListRegistry()
}