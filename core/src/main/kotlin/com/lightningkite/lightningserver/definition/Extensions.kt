package com.lightningkite.lightningserver.definition

public interface Extensions {
    public interface Key<T : Any>

    public operator fun <T : Any> get(key: Key<T>): T?
    public val entries: Set<Map.Entry<Key<*>, Any>>
}


public class MutableExtensions: Extensions {
    /**
     * For values that can be overwritten in MutableExtensions
     * */
    public interface Key<T : Any> : Extensions.Key<T>

    /**
     * Retrieves a mutable value `M` when in the context of [MutableExtensions], but when
     * degraded to [Extensions] retrieves `T`, which is an immutable version of `M`.
     *
     * Example:
     * ```kotlin
     * // Key that degrades from a MutableList to List
     * object ListKey : MutableExtensions.DegradingKey<MutableList<Int>, List<Int>>
     *
     * fun main() {
     *    val mutableExtensions = MutableExtensions()
     *    val upgraded: MutableList<Int> = mutableExtensions[ListKey]
     *
     *    // degrading MutableExtensions to Extensions
     *    val extensions: Extensions = mutableExtensions
     *
     *    // MutableList is degraded to List in Extensions
     *    val degraded: List<Int> = extensions[ListKey]
     * }
     * ```
     * */
    public interface DegradingKey<M : T, T : Any> : Extensions.Key<T> {
        public fun default(): M
    }

    private val _extensions: MutableMap<Extensions.Key<*>, Any> = HashMap()

    @Suppress("UNCHECKED_CAST")
    override operator fun <T : Any> get(key: Extensions.Key<T>): T? = _extensions[key] as? T

    @Suppress("UNCHECKED_CAST")
    public operator fun <M : T, T : Any> get(key: DegradingKey<M, T>): M =
        _extensions.getOrPut(key, key::default) as M

    public operator fun <T : Any> set(key: Key<T>, value: T?) {
        if (value == null) _extensions.remove(key)
        else _extensions[key] = value
    }

    override val entries: Set<Map.Entry<Extensions.Key<*>, Any>>
        get() = _extensions.entries

    public fun include(extensions: Extensions) {
        for ((key, value) in extensions.entries) {
            _extensions.putIfAbsent(key, value)
        }
    }
}

public interface Extended {
    public val extensions: Extensions
}

public interface Extendable : Extended {
    public override val extensions: MutableExtensions
}