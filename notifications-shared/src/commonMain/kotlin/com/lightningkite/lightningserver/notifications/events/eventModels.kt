package com.lightningkite.lightningserver.notifications.events

import com.lightningkite.services.data.Queryable
import com.lightningkite.services.database.*
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Anything that can serve as the type of a notification event, such as an application enum.
 *
 * [EventType] is the standard implementation.  The name is called [eventName] so that enums,
 * which already have a `name: String`, can implement this.
 *
 * @property eventName The unique identifier for this event type; used in subscription keys and URLs
 * @property tags Optional set of tags for categorizing or filtering events
 */
public interface EventTypeLike {
    public val eventName: EventType.Name
    public val tags: Set<String> get() = emptySet()
}

/**
 * The standard [EventTypeLike]: a unique name with optional tags for categorization.
 *
 * Equality is based solely on the name - two EventTypes with the same name are considered equal
 * even if their tags differ.
 *
 * @property name The unique identifier for this event type
 * @property tags Optional set of tags for categorizing or filtering events
 */
@Queryable
public data class EventType(
    val name: Name,
    override val tags: Set<String> = emptySet(),
) : EventTypeLike {
    override val eventName: Name get() = name

    @Queryable
    @JvmInline
    public value class Name(override val raw: String) : TypedId<String, Name> {
        override fun toString(): String = raw
    }

    override fun equals(other: Any?): Boolean = other is EventType && name == other.name
    override fun hashCode(): Int = name.hashCode() + 17
}

/**
 * Represents an untyped event occurrence in the system.
 *
 * This is the database-stored form of an event, where the subject ID is serialized to JSON.
 * Use [TypedEvent] in application code for type-safe event handling.
 *
 * @property _id Unique identifier for this event occurrence
 * @property timestamp When the event occurred
 * @property type The type of event that occurred
 * @property subject JSON-serialized ID of the subject entity this event relates to
 */
@Queryable
public data class EventData<E : EventTypeLike>(
    override val _id: Uuid,
    val timestamp: Instant,
    val type: E,
    val subject: IdJsonEncoded,
) : HasId<Uuid> {
    @Serializable
    @JvmInline
    public value class IdJsonEncoded private constructor(public val rawJson: String) {
        public fun <ID> decode(json: Json, serializer: DeserializationStrategy<ID>): ID =
            json.decodeFromString(serializer, rawJson)

        public companion object {
            public fun <ID> encode(json: Json, serializer: SerializationStrategy<ID>, id: ID): IdJsonEncoded =
                IdJsonEncoded(json.encodeToString(serializer, id))
        }
    }

    public inline fun <reified ID> subjectId(json: Json): ID = subject.decode(json, serializerOrContextual())
}


/**
 * Composite key representing a user's relationship to an event type.
 *
 * Used as an identifier for user-specific event subscriptions and notification preferences.
 * Implements [Comparable] to enable ordered storage and retrieval.
 *
 * @param UID The type of user identifier (must be comparable)
 * @property user The user identifier
 * @property event The event type identifier
 */
@Queryable
public data class UserEventType<UID : Comparable<UID>>(
    val user: UID,
    val event: EventType.Name,
) : Comparable<UserEventType<UID>> {
    override fun compareTo(other: UserEventType<UID>): Int =
        user.compareTo(other.user).takeIf { it != 0 } ?: event.compareTo(other.event)
}
