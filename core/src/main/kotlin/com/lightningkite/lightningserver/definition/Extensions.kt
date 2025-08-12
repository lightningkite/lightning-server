package com.lightningkite.lightningserver.definition


/**
 * [Extensions] provides read-only access to strongly-typed extension values. Extension values
 * are accessed by an [Extensions.Key]. For read-write access see [MutableExtensions].
 * */
public interface Extensions {
    /**
     * A key that retrieves an extension of type `T` from [Extensions], if it exists.
     *
     * Keys can also perform read-only property delegation for [Extended] receivers to
     * retrieve values of type `T?`.
     *
     * Example:
     * ```kotlin
     * class Util {
     *    object Key : Extensions.Key<Util>
     * }
     *
     * interface Foo : Extended
     *
     * val Foo.util: Util? by Util.Key
     * ```
     * */
    public interface Key<T : Any>

    /**
     * Retrieves the extension of type `T` for this [Key], if it exists. If the key is
     * not present returns `null`.
     * */
    public operator fun <T : Any> get(key: Key<T>): T?
    public val entries: Set<Map.Entry<Key<*>, Any>>
}

/**
 * [MutableExtensions] provides read-write access to strongly-typed extension values.
 * Write access is provided in two ways: [MutableExtensions.Key] and [MutableExtensions.DegradingKey].
 * */
public class MutableExtensions: Extensions {
    /**
     * Provides direct read-write access to an extension value of type `T`.
     *
     * [MutableExtensions.Key] can also perform read-write property delegation
     * for [Extendable] receivers.
     *
     * Example:
     *```kotlin
     *   class Util {
     *      object Key : MutableExtensions.Key<Util>
     *   }
     *
     *   interface Foo : Extendable
     *
     *   var Foo.util: Util? by Util.Key
     *   // Non-null delegation can be done by providing a default value
     *   var Foo.utilNotNull: Util by Util.Key.cached { Util() }
     *```
     * */
    public interface Key<T : Any> : Extensions.Key<T>

    /**
     * Retrieves `WRITE` when in the context of [MutableExtensions], but when
     * degraded to [Extensions] retrieves `READ`. The typical use for this
     * is to degrade a type `WRITE` with read-write access to a read-only type `READ`.
     *
     * Example:
     * ```kotlin
     * // Key that degrades from a MutableList to List
     * object Key : MutableExtensions.DegradingKey<MutableList<Int>, List<Int>>
     *
     * fun main() {
     *    val mutableExtensions = MutableExtensions()
     *    val upgraded: MutableList<Int> = mutableExtensions[Key]
     *
     *    // degrading MutableExtensions to Extensions
     *    val extensions: Extensions = mutableExtensions
     *
     *    // MutableList is degraded to List in Extensions
     *    val degraded: List<Int> = extensions[Key]
     * }
     * ```
     * */
    public interface DegradingKey<WRITE : READ, READ : Any> : Extensions.Key<READ> {
        public fun default(): WRITE
    }

    private val map: MutableMap<Extensions.Key<*>, Any> = HashMap()

    @Suppress("UNCHECKED_CAST")
    override operator fun <T : Any> get(key: Extensions.Key<T>): T? = map[key] as? T

    @Suppress("UNCHECKED_CAST")
    public operator fun <W : R, R : Any> get(key: DegradingKey<W, R>): W =
        map.getOrPut(key, key::default) as W

    /**
     * Adds the extension [value] to the internal extension map. If the [key] already
     * exists in the map its value is overwritten. If the value is `null` any existing
     * extension is removed.
     * */
    public operator fun <T : Any> set(key: Key<T>, value: T?) {
        if (value == null) map.remove(key)
        else map[key] = value
    }

    override val entries: Set<Map.Entry<Extensions.Key<*>, Any>>
        get() = map.entries

    public fun include(extensions: Extensions) {
        for ((key, value) in extensions.entries) {
            map.putIfAbsent(key, value)
        }
    }
}

/**
 * [Extended] types provide an instance of [Extensions], which in turn provides access
 * to typed extension values. This is useful when specifying that a type may have
 * additional property extensions defined and accessible through an [Extensions.Key].
 *
 * [Extended] provides only read-only access to pre-defined extensions. By convention,
 * publicly defined extensions for an [Extended] type should be accessible through extension
 * properties, as this makes identifying relevant extensions easier.
 *
 * Example
 * ```kotlin
 * class Util {
 *    object Extension : Extensions.Key<Util>
 * }
 *
 * interface Foo : Extended
 *
 * // Extension publicly exposed through extension property
 * val Foo.util: Util? by Util.Extension
 * ```
 * */
public interface Extended {
    public val extensions: Extensions
}

/**
 * [Extendable] types provide an instance of [MutableExtensions], which in turn provides
 * read-write access to typed extension values. [Extendable] types are useful as they
 * allow you to easily define extension properties with non-static storage. Extension values
 * added to an [Extendable] are unique to that [Extendable] instance.
 *
 * By convention, publicly defined extensions for an [Extendable] type should be accessible
 * through extension properties that delegate to a [MutableExtensions.Key] or [MutableExtensions.DegradingKey],
 * as this makes identifying relevant extensions easier.
 *
 * Example
 * ```kotlin
 * class Config {
 *    object Extension : MutableExtensions.Key<Config>
 * }
 *
 * interface Foo : Extendable
 *
 * // Extension publicly exposed through extension property
 * var Foo.config: Config? by Config.Extension
 *
 * fun example() {
 *     val foo: Foo = // ...
 *     foo.config = Config() // Can set values for this `Foo` instance
 *     val value = foo.config // Can read values
 * }
 * ```
 *
 * @see MutableExtensions
 * @see MutableExtensions.Key
 * @see MutableExtensions.DegradingKey
 * */
public interface Extendable : Extended {
    public override val extensions: MutableExtensions
}