package com.lightningkite.lightningserver.notifications.events

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.*
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Represents a type of event that can occur in the system.
 *
 * Events are identified by a unique name and can have associated tags for categorization.
 * Equality is based solely on the name - two EventTypes with the same name are considered equal
 * even if their tags differ.
 *
 * @property name The unique identifier for this event type
 * @property tags Optional set of tags for categorizing or filtering events
 */
@Serializable
@GenerateDataClassPaths
public data class EventType(
    val name: Name,
    val tags: Set<String> = emptySet(),
) {
    @Serializable
    @JvmInline
    @GenerateDataClassPaths
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
@Serializable
@GenerateDataClassPaths
public data class EventData(
    override val _id: Uuid,
    val timestamp: Instant,
    val type: EventType,
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
@Serializable
@GenerateDataClassPaths
public data class UserEventType<UID : Comparable<UID>>(
    val user: UID,
    val event: EventType.Name,
) : Comparable<UserEventType<UID>> {
    override fun compareTo(other: UserEventType<UID>): Int =
        user.compareTo(other.user).takeIf { it != 0 } ?: event.compareTo(other.event)
}
