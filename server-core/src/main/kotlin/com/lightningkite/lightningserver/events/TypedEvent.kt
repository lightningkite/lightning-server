package com.lightningkite.lightningserver.events

import com.lightningkite.UUID
import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.db.ModelInfo
import com.lightningkite.lightningserver.notifications.Event
import com.lightningkite.lightningserver.notifications.EventType
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.snakeCase
import com.lightningkite.lightningserver.tasks.task
import com.lightningkite.now
import kotlinx.datetime.Instant

class TypedEventType<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    val name: String,
    val tags: Set<String>,
    val info: ModelInfo<USER, T, ID>,
    registry: EventRegistry<USER>
) {
    val type = EventType(name, tags)

    override fun toString(): String = name

    val conditionSerializer = Condition.serializer(info.serialization.serializer)

    init {
        registry.register(this)
    }
}

data class TypedEvent<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    override val _id: UUID,
    val time: Instant,
    val type: TypedEventType<USER, T, ID>,
    val subject: T
): HasId<UUID> {
    constructor(type: TypedEventType<USER, T, ID>, subject: T) : this(UUID.random(), now(), type, subject)
    fun toEvent() = Event(
        _id = _id,
        timestamp = time,
        type = type.type,
        subject = Serialization.json.encodeToString(type.info.serialization.idSerializer, subject._id)
    )
}
