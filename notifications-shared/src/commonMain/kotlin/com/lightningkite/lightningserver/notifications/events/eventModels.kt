package com.lightningkite.lightningserver.notifications.events

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

public typealias UntypedID = String

@Serializable
@GenerateDataClassPaths
public data class EventType(
    val name: String,
    val tags: Set<String> = emptySet()
) {
    override fun equals(other: Any?): Boolean = other is EventType && name == other.name
    override fun hashCode(): Int = name.hashCode()
}

@Serializable
@GenerateDataClassPaths
public data class Event(
    override val _id: Uuid = Uuid.random(),
    val timestamp: Instant,
    val type: EventType,
    val subject: UntypedID  // JSON of ID of T
): HasId<Uuid>


@Serializable
@GenerateDataClassPaths
public data class UserEventType<UID : Comparable<UID>>(
    val user: UID,
    val type: EventType
) : Comparable<UserEventType<UID>> {
    override fun compareTo(other: UserEventType<UID>): Int =
        user.compareTo(other.user).takeIf { it != 0 } ?: type.name.compareTo(other.type.name)
}
