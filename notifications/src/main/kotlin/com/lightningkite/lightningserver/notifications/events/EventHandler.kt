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

/**
 * Interface for handling typed events in the notification system.
 *
 * Implementations define how events are processed, typically by determining
 * which users should be notified and generating notification content.
 *
 * @param USER The user type (nullable for public events)
 */
public interface EventHandler<USER : HasId<*>?> {
    public val registry: EventRegistry<USER>

    context(runtime: ServerRuntime)
    public suspend fun <T : HasId<ID>, ID : Comparable<ID>> handle(event: TypedEvent<USER, T, ID>)
}

/**
 * Provides task-based event launching capabilities for a specific event type.
 *
 * This class creates a task endpoint that can process events either inline (immediate handling)
 * or asynchronously through the task system. Events can be triggered by calling the task
 * directly or using the invoke operator.
 *
 * **Important:** The [task] endpoint is automatically mounted at `/events/{eventName}`.
 *
 * @param USER The user type (nullable for public events)
 * @param T The subject entity type
 * @param ID The ID type of the subject entity
 * @property type The event type definition
 * @property handler The event handler that processes this event type
 * @property name Convenience accessor for the event type name
 * @property task The task endpoint for asynchronous event processing
 */
public class EventLauncher<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> internal constructor(
    public val type: TypedEventType<USER, T, ID>,
    public val handler: EventHandler<USER>,
    timeout: Duration = 5.minutes,
) : ServerBuilder() {
    public val name: String get() = type.name

    /**
     * Handles the event inline without going through the task system.
     * Useful for immediate processing or in unit tests.
     */
    context(_: ServerRuntime)
    public suspend fun handleInline(subject: T) {
        handler.handle(TypedEvent(type, subject))
    }

    public val task: Task<T> = path bind Task(type.info.serializer, timeout) { handleInline(it) }

    /**
     * Launches the event asynchronously through the task system.
     */
    context(_: ServerRuntime)
    public suspend operator fun invoke(subject: T): Unit = task(subject)
}

/**
 * DSL function to define and register an event type with its handler.
 *
 * This function creates a [TypedEventType], registers it with the handler's registry,
 * and creates an [EventLauncher] with a task endpoint at `/events/{name}`.
 *
 * @param name The unique name for this event type
 * @param info Model information for the subject entity type
 * @param tags Optional tags for categorizing this event type
 * @param timeout Maximum execution time for event processing (default: 5 minutes)
 * @param additionalSetup Optional callback for additional setup (e.g., setting content generators)
 * @return The created event launcher
 */
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

/*
 * TODO: API Recommendations for EventHandler.kt:
 *
 * 3. Add helper for batch event processing if multiple events of the same type
 *    need to be launched together efficiently.
 */