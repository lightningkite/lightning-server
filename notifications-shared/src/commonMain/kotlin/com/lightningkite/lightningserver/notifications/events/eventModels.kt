package com.lightningkite.lightningserver.notifications.events

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Type alias for untyped IDs stored as JSON strings.
 * Used to store the serialized ID of an event subject without type information.
 */
public typealias UntypedID = String

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
    val name: String,
    val tags: Set<String> = emptySet()
) {
    override fun equals(other: Any?): Boolean = other is EventType && name == other.name
    override fun hashCode(): Int = name.hashCode()
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
public data class Event(
    override val _id: Uuid = Uuid.random(),
    val timestamp: Instant,
    val type: EventType,
    val subject: UntypedID  // JSON of ID of T
): HasId<Uuid>


/**
 * Composite key representing a user's relationship to an event type.
 *
 * Used as an identifier for user-specific event subscriptions and notification preferences.
 * Implements [Comparable] to enable ordered storage and retrieval.
 *
 * @param UID The type of user identifier (must be comparable)
 * @property user The user identifier
 * @property type The event type
 */
@Serializable
@GenerateDataClassPaths
public data class UserEventType<UID : Comparable<UID>>(
    val user: UID,
    val type: EventType
) : Comparable<UserEventType<UID>> {
    override fun compareTo(other: UserEventType<UID>): Int =
        user.compareTo(other.user).takeIf { it != 0 } ?: type.name.compareTo(other.type.name)
}
