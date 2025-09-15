package com.lightningkite.lightningserver.definition.builder

import com.lightningkite.toSealedMap

/**
 * An [Map] that allows you to add items to it. Once an item is added
 * to a [MapRegistry], it is considered immutable. It cannot be overwritten or removed.
 * */
public interface MapRegistry<L, V> : Map<L, V> {
    /**
     * Adds the [value] to the underlying [Map] with the given [location].
     *
     * Unlike [MutableMap], registering two values to the same location will throw a [DuplicateRegistrationError].
     * The value at each location is considered immutable once it has been set.
     * */
    public fun register(location: L, value: V)
}

public class DuplicateRegistrationError(message: String, public val initial: Any?, public val overwrite: Any?) : Error(message)


public fun <L, V> MapRegistry<L, V>.include(map: Map<L, V>) {
    for ((k, v) in map) register(k, v)
}

public fun <L, V> MapRegistry<L, V>.getOrRegister(location: L, defaultValue: () -> V): V =
    if (containsKey(location)) getValue(location)
    else {
        val default = defaultValue()
        register(location, default)
        default
    }

private class BasicMapRegistry<L, V>(
    private val registry: LinkedHashMap<L, V> = LinkedHashMap()
) : MapRegistry<L, V>, Map<L, V> by registry {
    override fun register(location: L, value: V) {
        if (registry.containsKey(location)) registry.getValue(location).let {
            throw DuplicateRegistrationError("Location $location already has a registered value: $it", it, value)
        }
        registry[location] = value
    }
}

public fun <L, V> MapRegistry(): MapRegistry<L, V> = BasicMapRegistry()