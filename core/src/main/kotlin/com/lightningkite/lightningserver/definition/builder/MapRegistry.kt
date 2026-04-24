package com.lightningkite.lightningserver.definition.builder

import com.lightningkite.services.data.toSealedMap

/**
 * A write-once [Map] used during server building to register items at unique locations.
 *
 * Unlike a standard [MutableMap], once an item is registered at a location, that location
 * becomes immutable - attempts to register a different value at the same location will throw
 * [DuplicateRegistrationError]. This prevents accidental overwriting of endpoints, tasks, and
 * other resources during server construction.
 *
 * This is used internally by [ServerBuilder] to register endpoints, tasks, schedules, and other
 * resources that must have unique paths or identifiers.
 *
 * @param L The type of the location/key
 * @param V The type of the value
 */
public interface MapRegistry<L, V> : Map<L, V> {
    /**
     * Registers a value at the given location.
     *
     * @param location The location/key where the value should be registered
     * @param value The value to register
     * @throws DuplicateRegistrationError if the location already has a registered value
     */
    public fun register(location: L, value: V)
}

/**
 * Thrown when attempting to register a value at a location that already has a registered value.
 *
 * This error helps catch configuration mistakes during server building, such as accidentally
 * defining the same endpoint path twice.
 *
 * @property initial The value that was originally registered at the location
 * @property overwrite The value that was attempted to be registered (and rejected)
 */
public class DuplicateRegistrationError(message: String, public val initial: Any?, public val overwrite: Any?) :
    Error(message)

/**
 * Registers all entries from a map into this registry.
 *
 * @param map The map whose entries should be registered
 * @throws DuplicateRegistrationError if any key already exists in the registry
 */
public fun <L, V> MapRegistry<L, V>.include(map: Map<L, V>) {
    for ((k, v) in map) register(k, v)
}

/**
 * Returns the existing value at a location, or registers and returns a default value if not present.
 *
 * This is useful when building nested structures where you want to reuse an existing registry
 * at a location or create a new one if it doesn't exist.
 *
 * @param location The location to check
 * @param defaultValue A function that produces the default value if the location is empty
 * @return The existing or newly registered value
 */
public fun <L, V> MapRegistry<L, V>.getOrRegister(location: L, defaultValue: () -> V): V =
    if (containsKey(location)) getValue(location)
    else {
        val default = defaultValue()
        register(location, default)
        default
    }

private class BasicMapRegistry<L, V>(
    private val registry: LinkedHashMap<L, V> = LinkedHashMap(),
) : MapRegistry<L, V>, Map<L, V> by registry {
    override fun register(location: L, value: V) {
        if (registry.containsKey(location)) registry.getValue(location).let {
            throw DuplicateRegistrationError("Key $location already has a registered value: $it", it, value)
        }
        registry[location] = value
    }

    override fun toString(): String = "MapRegistry(${registry.entries.joinToString { "${it.key} to ${it.value}" }})"
}

/**
 * Creates an empty [MapRegistry].
 *
 * @return A new empty registry
 */
public fun <L, V> MapRegistry(): MapRegistry<L, V> = BasicMapRegistry()

/**
 * Builds an immutable map using a [MapRegistry].
 *
 * The [setup] function receives a mutable registry to populate, which is then
 * converted to an immutable sealed map.
 *
 * @param setup A function that populates the registry
 * @return An immutable map containing all registered entries
 */
public fun <L, V> buildMapRegistry(setup: MapRegistry<L, V>.() -> Unit): Map<L, V> =
    MapRegistry<L, V>().apply(setup).toSealedMap()