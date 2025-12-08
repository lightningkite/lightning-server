package com.lightningkite.lightningserver.definition.builder

import com.lightningkite.toSealedList

/**
 * An append-only [List] used during server building to accumulate items.
 *
 * Unlike a standard [MutableList], items cannot be removed once added, providing
 * a safer API for building immutable server definitions. This is used internally
 * by [ServerBuilder] to collect interceptors, settings, and other ordered resources.
 *
 * @param V The type of items in the registry
 */
public interface ListRegistry<V> : List<V> {
    /**
     * Adds an item to this registry.
     *
     * Items are added in order and cannot be removed once registered.
     *
     * @param value The item to register
     */
    public fun register(value: V)
}

private data class BasicListRegistry<V>(
    private val registry: ArrayList<V> = ArrayList()
) : ListRegistry<V>, List<V> by registry {
    override fun register(value: V) { registry.add(value) }
}

/**
 * Registers all items from a list into this registry.
 *
 * @param values The list of values to register
 */
public fun <V> ListRegistry<V>.include(values: List<V>) {
    for (value in values) register(value)
}

/**
 * Creates an empty [ListRegistry].
 *
 * @return A new empty registry
 */
public fun <V> ListRegistry(): ListRegistry<V> = BasicListRegistry()

/**
 * Creates a [ListRegistry] pre-populated with items.
 *
 * @param items Initial items for the registry
 * @return A new registry containing the items
 */
public fun <V> ListRegistry(items: List<V>): ListRegistry<V> = BasicListRegistry(ArrayList(items))
public fun <V> ListRegistry(vararg items: V): ListRegistry<V> = BasicListRegistry(ArrayList(items.toList()))

/**
 * Builds an immutable list using a [ListRegistry].
 *
 * The [setup] function receives a mutable registry to populate, which is then
 * converted to an immutable sealed list.
 *
 * @param setup A function that populates the registry
 * @return An immutable list containing all registered items
 */
public fun <V> buildListRegistry(setup: ListRegistry<V>.() -> Unit): List<V> = ListRegistry<V>().apply(setup).toSealedList()