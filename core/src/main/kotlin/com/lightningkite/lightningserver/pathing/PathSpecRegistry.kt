package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.definition.builder.DuplicateRegistrationException
import com.lightningkite.lightningserver.definition.builder.MapRegistry

public interface PathSpecRegistry<V> : PathSpecMap<V>, MapRegistry<PathSpec, V>

internal class PathSpecRegistryImpl<V>(
    internal val wraps: MutablePathSpecMap<V> = MutablePathSpecMap(),
) : PathSpecRegistry<V>, PathSpecMap<V> by wraps {
    override fun register(location: PathSpec, value: V) {
        wraps[location]?.let {
            throw DuplicateRegistrationException("PathSpecRegistry already contains value $it at path $location", it, value)
        }

        wraps[location] = value
    }
}

public fun <V> PathSpecRegistry(): PathSpecRegistry<V> = PathSpecRegistryImpl()

public fun <V> buildPathSpecRegistry(setup: PathSpecRegistry<V>.() -> Unit): PathSpecMap<V> =
    PathSpecRegistry<V>().apply(setup)

