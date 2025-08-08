package com.lightningkite.lightningserver.definition.builder

/**
 * An [Map] that allows you to add items to it. Once an item is added
 * to a [Registry], it is considered immutable. It cannot be overwritten or removed.
 * */
public interface Registry<L, V : Any> : Map<L, V> {
    /**
     * Adds the [value] to the underlying [Map] with the given [location].
     *
     * Unlike [MutableMap], registering two values to the same location will throw a [DuplicateRegistrationError].
     * The value at each location is considered immutable once it has been set.
     * */
    public fun register(location: L, value: V)
}

public class DuplicateRegistrationError(message: String, public val initial: Any, public val overwrite: Any) : Error(message)


private class BasicRegistry<L, V : Any>(
    private val registry: HashMap<L, V> = HashMap()
) : Registry<L, V>, Map<L, V> by registry {
    override fun register(location: L, value: V) {
        registry[location]?.let {
            throw DuplicateRegistrationError("Location $location already has a registered value: $it", it, value)
        }
        registry[location] = value
    }
}

public fun <L, V : Any> Registry(): Registry<L, V> = BasicRegistry()