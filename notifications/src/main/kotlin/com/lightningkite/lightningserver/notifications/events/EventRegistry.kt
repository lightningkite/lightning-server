package com.lightningkite.lightningserver.notifications.events

import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.services.data.toSealedMap

public class EventRegistry private constructor(
    private val registry: MapRegistry<EventType.Name, EventDefinition<*, *>>,
) : Map<EventType.Name, EventDefinition<*, *>> by registry {
    public constructor() : this(MapRegistry())

    public fun register(definition: EventDefinition<*, *>) {
        registry[definition.name]?.let { registered ->
            if (registered != definition) throw DuplicateRegistrationException(
                "EventType name ${registered.name} is not unique. EventTypes require unique names.",
                registered,
                definition
            )
            return
        }
        registry.register(definition.name, definition)
    }

    private object Key : MutableExtensions.WritableKey<EventRegistry, Map<EventType.Name, EventDefinition<*, *>>> {
        override fun default(): EventRegistry = EventRegistry()
        override fun EventRegistry.include(other: Map<EventType.Name, EventDefinition<*, *>>) = registry.include(other)
        override fun seal(data: Map<EventType.Name, EventDefinition<*, *>>): Map<EventType.Name, EventDefinition<*, *>> =
            data.toSealedMap()
    }

    public companion object {
        public val ServerBuilder.events: EventRegistry by Key
        public val ServerDefinition.events: Map<EventType.Name, EventDefinition<*, *>> by Key
    }
}