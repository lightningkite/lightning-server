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
    public interface MutableKey<T : Any> : Extensions.Key<T>

    /**
     * Meant to retrieve a mutable version of T when in the context of MutableExtensions,
     * and an immutable version when reduced to Extensions.
     * */
    public interface DegradingKey<T : Any, M : T> : Extensions.Key<T> {
        public fun default(): M
    }

    private val _extensions: MutableMap<Extensions.Key<*>, Any> = HashMap()

    @Suppress("UNCHECKED_CAST")
    override operator fun <T : Any> get(key: Extensions.Key<T>): T? = _extensions[key] as? T

    @Suppress("UNCHECKED_CAST")
    public operator fun <T : Any, M : T> get(key: DegradingKey<T, M>): M =
        _extensions.getOrPut(key, key::default) as M

    public operator fun <T : Any> set(key: MutableKey<T>, value: T?) {
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