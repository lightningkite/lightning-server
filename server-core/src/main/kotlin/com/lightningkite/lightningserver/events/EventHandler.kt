package com.lightningkite.lightningserver.events

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.db.ModelInfo
import com.lightningkite.lightningserver.snakeCase
import com.lightningkite.lightningserver.tasks.task

interface EventHandler<USER : HasId<*>?> {
    val registry: EventRegistry<USER>

    suspend fun <T : HasId<ID>, ID : Comparable<ID>> handle(event: TypedEvent<USER, T, ID>)
}

data class EventLauncher<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    val type: TypedEventType<USER, T, ID>,
    val handler: EventHandler<USER>
) {
    suspend operator fun invoke(subject: T) = handler.handle(TypedEvent(type, subject))

    val task = task("${type.name.snakeCase()}-launcher", type.info.serialization.serializer) {
        this@EventLauncher.invoke(it)
    }

    suspend fun launch(subject: T) = task(subject)
}

fun <HANDLER : EventHandler<USER>, USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> HANDLER.event(
    name: String,
    info: ModelInfo<USER, T, ID>,
    tags: Set<String> = emptySet(),
    additionalSetup: HANDLER.(TypedEventType<USER, T, ID>) -> Unit = {}
): EventLauncher<USER, T, ID> {
    val type = TypedEventType(name, tags, info, registry)

    additionalSetup(type)

    return EventLauncher(type, this)
}