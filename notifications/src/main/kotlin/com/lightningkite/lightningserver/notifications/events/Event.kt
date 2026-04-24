package com.lightningkite.lightningserver.notifications.events

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.HasId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Represents a type-safe event type definition.
 *
 * This class maintains type information for events and automatically registers itself
 * with the provided registry during construction. Each event type has associated model
 * information that enables type-safe filtering and serialization.
 *
 * @param T The subject entity type
 * @param ID The ID type of the subject entity
 * @property untyped The untyped event type (name and tags)
 * @property info Model information for the subject entity
 * @property conditionSerializer Serializer for type-safe conditions on the subject entity
 */
public data class EventDefinition<T : HasId<ID>, ID : Comparable<ID>>(
    public val untyped: EventType,
    public val info: ModelInfo<*, T, ID>,
) {
    public constructor(
        name: String,
        tags: Set<String>,
        info: ModelInfo<*, T, ID>,
    ) : this(EventType(EventType.Name(name), tags), info)

    public val name: EventType.Name get() = untyped.name
    public val tags: Set<String> get() = untyped.tags

    override fun toString(): String = name.raw

    public val conditionSerializer: KSerializer<Condition<T>> = Condition.serializer(info.serializer)
}

/**
 * Represents a type-safe event occurrence.
 *
 * Unlike [EventData], this maintains full type information for the subject entity,
 * enabling type-safe operations in application code. Can be converted to an
 * untyped [EventData] for database storage.
 *
 * @param T The subject entity type
 * @param ID The ID type of the subject entity
 * @property _id Unique identifier for this event occurrence
 * @property time When the event occurred
 * @property type The event type definition
 * @property subject The actual subject entity (not just its ID)
 */
public data class Event<T : HasId<ID>, ID : Comparable<ID>>(
    override val _id: Uuid,
    val time: Instant,
    val type: EventDefinition<T, ID>,
    val subject: T,
) : HasId<Uuid> {
    /**
     * Converts this typed event to an untyped [EventData] for storage.
     *
     * The subject entity is reduced to just its ID, which is serialized to JSON.
     *
     * @param json The JSON serializer to use for the subject ID
     */
    public fun data(json: Json): EventData = EventData(
        _id = _id,
        timestamp = time,
        type = type.untyped,
        subject = EventData.IdJsonEncoded.encode(json, type.info.idSerializer, subject._id)
    )

    context(server: ServerRuntime)
    public fun toExternalEventData(): EventData = data(server.externalSerialization.json)

    context(server: ServerRuntime)
    public fun toInternalEventData(): EventData = data(server.internalSerialization.json)
}

/**
 * Factory function to create a [TypedEvent] with a generated ID and current timestamp.
 *
 * @param type The event type definition
 * @param subject The subject entity that this event relates to
 */
context(server: ServerRuntime)
public fun <T : HasId<ID>, ID : Comparable<ID>> Event(
    type: EventDefinition<T, ID>,
    subject: T,
): Event<T, ID> = Event(Uuid.random(), now(), type, subject)
