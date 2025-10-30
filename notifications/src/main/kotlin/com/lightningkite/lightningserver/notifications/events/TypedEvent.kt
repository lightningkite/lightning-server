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

public class TypedEventType<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    public val type: EventType,
    public val info: ModelInfo<USER, T, ID>,
    registry: EventRegistry<USER>
) {
    public constructor(
        name: String,
        tags: Set<String>,
        info: ModelInfo<USER, T, ID>,
        registry: EventRegistry<USER>
    ) : this(EventType(name, tags), info, registry)

    public val name: String get() = type.name
    public val tags: Set<String> get() = type.tags

    override fun toString(): String = name

    public val conditionSerializer: KSerializer<Condition<T>> = Condition.serializer(info.serializer)

    init {
        registry.register(this)
    }
}

public data class TypedEvent<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    override val _id: Uuid,
    val time: Instant,
    val type: TypedEventType<USER, T, ID>,
    val subject: T
): HasId<Uuid> {
    public fun toEvent(json: Json): Event = Event(
        _id = _id,
        timestamp = time,
        type = type.type,
        subject = json.encodeToString(type.info.idSerializer, subject._id)
    )

    context(server: ServerRuntime)
    public fun toExternalEvent(): Event = toEvent(server.externalSerialization.json)

    context(server: ServerRuntime)
    public fun toInternalEvent(): Event = toEvent(server.internalSerialization.json)
}

context(server: ServerRuntime)
public fun <USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> TypedEvent(
    type: TypedEventType<USER, T, ID>,
    subject: T
): TypedEvent<USER, T, ID> = TypedEvent(Uuid.random(), now(), type, subject)