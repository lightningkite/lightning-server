package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.definition.builder.ListRegistry
import com.lightningkite.lightningserver.definition.builder.Registry
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import kotlin.reflect.KProperty

public operator fun <T : Any> Extensions.Key<T>.getValue(thisRef: Extended, property: KProperty<*>): T? =
    thisRef.extensions[this]

public operator fun <T : Any> MutableExtensions.MutableKey<T>.setValue(thisRef: Extendable, property: KProperty<*>, value: T?) {
    thisRef.extensions[this] = value
}

public operator fun <T : Any, M : T> MutableExtensions.DegradingKey<T, M>.getValue(thisRef: Extendable, property: KProperty<*>): M =
    thisRef.extensions[this]

public operator fun <T : Any, M : T> MutableExtensions.DegradingKey<T, M>.getValue(thisRef: Extended, property: KProperty<*>): T =
    thisRef.extensions[this] ?: default()

public interface RegistryExtension<L, V : Any> : MutableExtensions.DegradingKey<Map<L, V>, Registry<L, V>> {
    override fun default(): Registry<L, V> = Registry()
}
public interface ListRegistryExtension<V> : MutableExtensions.DegradingKey<List<V>, ListRegistry<V>> {
    override fun default(): ListRegistry<V> = ListRegistry()
}

context(extendable: Extendable)
public fun <L, V : Any> RegistryExtension<L, V>.register(location: L, value: V) {
    extendable.extensions[this].register(location, value)
}

context(extendable: Extendable)
public fun <V> ListRegistryExtension<V>.register(value: V) {
    extendable.extensions[this].register(value)
}



public operator fun <T : Any> ServerBuilder.get(key : Extensions.Key<T>): T? =
    extensions[key]

public operator fun <T : Any> ServerDefinition.get(key : Extensions.Key<T>): T? =
    extensions[key]