package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.definition.builder.ListRegistry
import com.lightningkite.lightningserver.definition.builder.MapRegistry
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Enables property delegation for [Extensions.Key] on [Extended] types, providing read-only access.
 *
 * This operator allows [Extensions.Key] instances to be used with Kotlin's `by` delegation syntax,
 * creating extension properties that retrieve values from the receiver's extensions.
 *
 * @param thisRef the [Extended] instance containing the extensions
 * @return the value associated with this key, or `null` if not present
 */
public operator fun <T : Any> Extensions.Key<T>.getValue(thisRef: Extended, property: KProperty<*>): T? =
    thisRef.extensions[this]

/**
 * Enables property delegation for [MutableExtensions.Key] on [Extendable] types, providing write access.
 *
 * This operator allows [MutableExtensions.Key] instances to be used with Kotlin's `by` delegation syntax
 * for mutable properties, enabling both reading and writing of extension values.
 *
 * @param thisRef the [Extendable] instance where the extension will be stored
 * @param value the value to associate with this key, or `null` to remove the key
 */
public operator fun <T : Any> MutableExtensions.Key<T>.setValue(thisRef: Extendable, property: KProperty<*>, value: T?) {
    thisRef.extensions[this] = value
}

public fun <T : Any> MutableExtensions.getOrPut(key: MutableExtensions.Key<T>, default: () -> T): T = get(key) ?: default().also { set(key, it) }

/**
 * Creates a read-write property delegate that caches values using this key.
 *
 * The returned delegate ensures that a value always exists for the property by computing a default
 * value on first access. The default value is computed using the receiver instance, allowing
 * context-aware initialization.
 *
 * Example:
 * ```kotlin
 * object ConfigKey : MutableExtensions.Key<Config>
 *
 * var MyBuilder.config: Config by ConfigKey.cache { Config(defaultName = "builder-${hashCode()}") }
 *
 * fun example() {
 *     val builder = MyBuilder()
 *     val config = builder.config // Creates default Config with unique name
 *     builder.config = Config("custom") // Can still be overwritten
 * }
 * ```
 *
 * @param default a function that computes the initial value using the receiver instance
 * @return a [ReadWriteProperty] that ensures the property always has a value
 */
public fun <T : Any, E : Extendable> MutableExtensions.Key<T>.cache(default: E.() -> T): ReadWriteProperty<E, T> =
    object : ReadWriteProperty<E, T> {
        override fun getValue(thisRef: E, property: KProperty<*>): T = thisRef.extensions.getOrPut(this@cache) { default(thisRef) }
        override fun setValue(thisRef: E, property: KProperty<*>, value: T) { thisRef.extensions[this@cache] = value }
    }

/**
 * Enables property delegation for [MutableExtensions.DegradingKey] on [Extendable] types.
 *
 * This operator allows [MutableExtensions.DegradingKey] instances to be used with Kotlin's `by` delegation
 * syntax on [Extendable] receivers, providing access to the mutable `WRITE` type.
 *
 * @param thisRef the [Extendable] instance containing the mutable extensions
 * @param property the property being delegated (not used in implementation)
 * @return the mutable value of type [WRITE], creating a default if not present
 */
public operator fun <WRITE : READ, READ : Any> MutableExtensions.DegradingKey<WRITE, READ>.getValue(thisRef: Extendable, property: KProperty<*>): WRITE =
    thisRef.extensions[this]

/**
 * Enables property delegation for [MutableExtensions.DegradingKey] on [Extended] types.
 *
 * This operator allows [MutableExtensions.DegradingKey] instances to be used with Kotlin's `by` delegation
 * syntax on [Extended] receivers, providing access to the read-only `READ` type.
 *
 * @param thisRef the [Extended] instance containing the extensions
 * @param property the property being delegated (not used in implementation)
 * @return the read-only value of type [READ], or a new default instance if not present
 */
public operator fun <WRITE : READ, READ : Any> MutableExtensions.DegradingKey<WRITE, READ>.getValue(thisRef: Extended, property: KProperty<*>): READ =
    thisRef.extensions[this] ?: default()


/**
 * A convenience wrapper of [MutableExtensions.DegradingKey] for a [MapRegistry], providing an implementation of
 * [default].
 *
 * This interface simplifies the creation of degrading keys for Registry types, automatically providing
 * an empty Registry as the default value. When accessed through [MutableExtensions], returns a mutable
 * [MapRegistry]. When accessed through [Extensions], returns an immutable [Map].
 *
 * Example:
 * ```kotlin
 * object HandlersKey : RegistryExtension<String, RequestHandler>
 *
 * val MyBuilder.handlers: Registry<String, RequestHandler> by HandlersKey
 * val MyClass.handlers: Map<String, RequestHandler> by HandlersKey
 * ```
 */
public interface MapRegistryExtension<L, V : Any> : MutableExtensions.DegradingKey<MapRegistry<L, V>, Map<L, V>> {
    override fun default(): MapRegistry<L, V> = MapRegistry()
    override fun MapRegistry<L, V>.include(other: Map<L, V>) {
        for ((key, value) in other) register(key, value)
    }
}

/**
 * A convenience wrapper of [MutableExtensions.DegradingKey] for a [ListRegistry], providing an implementation of
 * [default].
 *
 * This interface simplifies the creation of degrading keys for ListRegistry types, automatically providing
 * an empty ListRegistry as the default value. When accessed through [MutableExtensions], returns a mutable
 * [ListRegistry]. When accessed through [Extensions], returns an immutable [List].
 *
 * Example:
 * ```kotlin
 * object NamesKey : ListRegistryExtension<String>
 *
 * val MyBuilder.names: ListRegistry<String> by NamesKey
 * val MyClass.names: List<String> by NamesKey
 * ```
 */
public interface ListRegistryExtension<V> : MutableExtensions.DegradingKey<ListRegistry<V>, List<V>> {
    override fun default(): ListRegistry<V> = ListRegistry()
    override fun ListRegistry<V>.include(other: List<V>) {
        for (value in other) register(value)
    }
}

public fun Extensions.toMutableExtensions(): MutableExtensions = this as? MutableExtensions ?: MutableExtensions(this)