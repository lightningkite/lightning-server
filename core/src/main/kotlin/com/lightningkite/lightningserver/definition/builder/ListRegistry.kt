package com.lightningkite.lightningserver.definition.builder

/**
 * A [List] that can have items added to it, but not removed.
 * */
public interface ListRegistry<V> : List<V> {
    public fun register(value: V)
}

private data class BasicListRegistry<V>(
    private val registry: ArrayList<V> = ArrayList()
) : ListRegistry<V>, List<V> by registry {
    override fun register(value: V) { registry.add(value) }
}

public fun <V> ListRegistry<V>.include(values: List<V>) {
    for (value in values) register(value)
}

public fun <V> ListRegistry(): ListRegistry<V> = BasicListRegistry()
public fun <V> ListRegistry(items: List<V>): ListRegistry<V> = BasicListRegistry(ArrayList(items))