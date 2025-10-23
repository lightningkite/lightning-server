package com.lightningkite.lightningserver.notifications.events

import com.lightningkite.lightningserver.LightningServerDsl
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.invoke
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.services.database.HasId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public interface EventHandler<USER : HasId<*>?> {
    public val registry: EventRegistry<USER>

    context(runtime: ServerRuntime)
    public suspend fun <T : HasId<ID>, ID : Comparable<ID>> handle(event: TypedEvent<USER, T, ID>)
}

public class EventLauncher<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> internal constructor(
    public val type: TypedEventType<USER, T, ID>,
    public val handler: EventHandler<USER>,
    timeout: Duration = 5.minutes,
) : ServerBuilder() {
    public val name: String get() = type.name

    context(_: ServerRuntime)
    public suspend fun handleInline(subject: T) {
        handler.handle(TypedEvent(type, subject))
    }

    public val task: Task<T> = path bind Task(type.info.serializer, timeout) { handleInline(it) }

    context(_: ServerRuntime)
    public suspend operator fun invoke(subject: T): Unit = task(subject)
}

@LightningServerDsl
context(builder: ServerBuilder)
public fun <HANDLER : EventHandler<USER>, USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> HANDLER.event(
    name: String,
    info: ModelInfo<USER, T, ID>,
    tags: Set<String> = emptySet(),
    timeout: Duration = 5.minutes,
    additionalSetup: HANDLER.(TypedEventType<USER, T, ID>) -> Unit = {}
): EventLauncher<USER, T, ID> {
    val type = TypedEventType(name, tags, info, registry)

    additionalSetup(type)

    val launcher = EventLauncher(type, this, timeout)

    with(builder) {
        path.path("events").path(type.name) include launcher
    }

    return launcher
}