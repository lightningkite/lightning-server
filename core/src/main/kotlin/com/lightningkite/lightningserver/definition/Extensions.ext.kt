package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.definition.builder.ListRegistry
import com.lightningkite.lightningserver.definition.builder.MapRegistry
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.toSealedList
import com.lightningkite.toSealedMap
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

/**
 * Retrieves the value associated with this [key], or computes and stores a default value if not present.
 *
 * This function behaves similarly to [MutableMap.getOrPut], ensuring thread-unsafe lazy initialization
 * of extension values. If the key is already present, its value is returned. Otherwise, [default] is
 * invoked, the result is stored, and then returned.
 *
 * Note: This is not thread-safe. If thread-safety is needed, consider using synchronization externally.
 *
 * @param key The key to look up or initialize
 * @param default A function that computes the default value if the key is not present
 * @return The existing or newly computed value for this key
 */
public inline fun <T : Any> MutableExtensions.getOrPut(key: MutableExtensions.Key<T>, default: () -> T): T = get(key) ?: default().also { set(key, it) }

/**
 * Enables property delegation for [MutableExtensions.WritableKey] on [Extendable] types.
 *
 * This operator allows [MutableExtensions.WritableKey] instances to be used with Kotlin's `by` delegation
 * syntax on [Extendable] receivers, providing access to the mutable `WRITE` type.
 *
 * @param thisRef the [Extendable] instance containing the mutable extensions
 * @param property the property being delegated (not used in implementation)
 * @return the mutable value of type [WRITE], creating a default if not present
 */
public operator fun <WRITE : READ, READ : Any> MutableExtensions.WritableKey<WRITE, READ>.getValue(thisRef: Extendable, property: KProperty<*>): WRITE =
    thisRef.extensions[this]

/**
 * Enables property delegation for [MutableExtensions.WritableKey] on [Extended] types.
 *
 * This operator allows [MutableExtensions.WritableKey] instances to be used with Kotlin's `by` delegation
 * syntax on [Extended] receivers, providing access to the read-only `READ` type.
 *
 * @param thisRef the [Extended] instance containing the extensions
 * @param property the property being delegated (not used in implementation)
 * @return the read-only value of type [READ], or a new default instance if not present
 */
public operator fun <WRITE : READ, READ : Any> MutableExtensions.WritableKey<WRITE, READ>.getValue(thisRef: Extended, property: KProperty<*>): READ =
    thisRef.extensions[this] ?: default()


/**
 * A convenience wrapper of [MutableExtensions.WritableKey] for a [MapRegistry], providing an implementation of
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
public interface MapRegistryExtension<L, V> : MutableExtensions.WritableKey<MapRegistry<L, V>, Map<L, V>> {
    override fun default(): MapRegistry<L, V> = MapRegistry()
    override fun MapRegistry<L, V>.include(other: Map<L, V>) {
        for ((key, value) in other) register(key, value)
    }
    override fun seal(data: Map<L, V>): Map<L, V> = data.toSealedMap()
}

/**
 * A convenience wrapper of [MutableExtensions.WritableKey] for a [ListRegistry], providing an implementation of
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
public interface ListRegistryExtension<V> : MutableExtensions.WritableKey<ListRegistry<V>, List<V>> {
    override fun default(): ListRegistry<V> = ListRegistry()
    override fun ListRegistry<V>.include(other: List<V>) {
        for (value in other) register(value)
    }
    override fun seal(data: List<V>): List<V> = data.toSealedList()
}

/**
 * Converts this read-only [Extensions] into a [MutableExtensions] by copying all entries.
 *
 * This creates a new mutable extensions container with all the entries from this extensions object.
 * Modifications to the returned [MutableExtensions] will not affect this original [Extensions].
 *
 * @return A new [MutableExtensions] instance containing copies of all entries from this extensions
 */
public fun Extensions.toMutableExtensions(): MutableExtensions = MutableExtensions(this)

public fun Extensions.sealed(): Extensions =
    this as? SealedExtensions ?: SealedExtensions(this)

context(extended: Extended)
public fun <T : Any> Extensions.Key<T>.get(): T? = extended.extensions[this]

context(extendable: Extendable)
public fun <W : R, R : Any> MutableExtensions.WritableKey<W, R>.get(): W = extendable.extensions[this]

context(extendable: Extendable)
public infix fun <T : Any> MutableExtensions.Key<T>.set(value: T?) {
    extendable.extensions[this] = value
}