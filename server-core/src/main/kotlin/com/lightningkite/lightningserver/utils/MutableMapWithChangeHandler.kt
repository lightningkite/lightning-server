package com.lightningkite.lightningserver.utils

internal class MutableMapWithChangeHandler<K, V>(val wraps: MutableMap<K, V> = mutableMapOf(), val onChange: ()->Unit): MutableMap<K, V> {
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = wraps.entries  // TODO: Handle mutation at this level
    override val keys: MutableSet<K> get() = wraps.keys  // TODO: Handle mutation at this level
    override val values: MutableCollection<V> get() = wraps.values  // TODO: Handle mutation at this level

    override fun clear() {
        wraps.clear()
        onChange()
    }

    override fun remove(key: K): V? {
        val result = wraps.remove(key)
        onChange()
        return result
    }

    override fun putAll(from: Map<out K, V>) {
        val result = wraps.putAll(from)
        onChange()
        return result
    }

    override fun put(key: K, value: V): V? {
        val result = wraps.put(key, value)
        onChange()
        return result
    }

    override val size: Int get() = wraps.size
    override fun isEmpty(): Boolean = wraps.isEmpty()
    override fun get(key: K): V? = wraps.get(key)
    override fun containsValue(value: V): Boolean = wraps.containsValue(value)
    override fun containsKey(key: K): Boolean = wraps.containsKey(key)
}