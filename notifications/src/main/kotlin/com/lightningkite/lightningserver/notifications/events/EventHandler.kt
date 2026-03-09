package com.lightningkite.lightningserver.notifications.events

import com.lightningkite.lightningserver.LightningServerDsl
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.notifications.events.EventRegistry.Companion.events
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.invoke
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.sdk.kabobCase
import com.lightningkite.services.database.HasId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Interface for handling typed events in the notification system.
 *
 * Implementations define how events are processed, typically by determining
 * which users should be notified and generating notification content.
 */
public interface EventHandler {
    context(runtime: ServerRuntime)
    public suspend fun <T : HasId<ID>, ID : Comparable<ID>> handle(event: Event<T, ID>)
}

/**
 * Provides task-based event launching capabilities for a specific event type.
 *
 * This is created by the `EventHandler.event(...)` dsl.
 *
 * This class creates a task endpoint that can process events either inline (immediate handling)
 * or asynchronously through the task system. Events can be triggered by calling the task
 * directly or using the invoke operator.
 *
 * **Important:** The [task] endpoint is automatically mounted at `/events/{eventName}`.
 *
 * ## Usage
 *
 * You use an [EventLauncher] by simply invoking it to notify an event has occurred. Ex.
 *
 * ```kotlin
 * object AppNotifications : NotificationEndpoints(...) {   // Notification Event Handler
 *    ...
 * }
 *
 * object ModelEndpoints : ServerBuilder() {
 *    val info = Server.database.modelInfo(
 *        ...,
 *        signals = { table ->
 *           table.postCreate { notifyCreated(it) } // invoke the launcher to launch the event
 *        }
 *    )
 *
 *    // Define an event (returns an `EventLauncher`)
 *    val notifyCreated = AppNotifications.event("Model Created", info) {
 *        ... // setup
 *    }
 * }
 * ```
 *
 * @param T The subject entity type
 * @param ID The ID type of the subject entity
 * @property event The event type definition
 * @property handler The event handler that processes this event type
 * @property name Convenience accessor for the event type name
 * @property task The task endpoint for asynchronous event processing
 */
public class EventLauncher<H : EventHandler, T : HasId<ID>, ID : Comparable<ID>> internal constructor(
    public val event: EventDefinition<T, ID>,
    public val handler: H,
    timeout: Duration = 5.minutes,
) : ServerBuilder() {
    public val name: EventType.Name get() = event.name

    /**
     * Handles the event inline without going through the task system.
     * Useful for immediate processing or in unit tests.
     */
    context(_: ServerRuntime)
    public suspend fun handleInline(subject: T) {
        handler.handle(Event(event, subject))
    }

    public val task: Task<T> = path bind Task(event.info.serializer, timeout) { handleInline(it) }

    /**
     * Launches the event asynchronously through the task system.
     */
    context(_: ServerRuntime)
    public suspend operator fun invoke(subject: T): Unit = task(subject)
}

/**
 * DSL function to define and register an event type with its handler.
 *
 * This function creates a [EventDefinition], registers it with the handler's registry,
 * and creates an [EventLauncher] with a task endpoint at `/events/{name.kabobCase()}`.
 *
 * ## Usage
 *
 * You use an [EventLauncher] by simply invoking it to notify an event has occurred. Ex.
 *
 * ```kotlin
 * object AppNotifications : NotificationEndpoints(...) {   // Notification Event Handler
 *    ...
 * }
 *
 * object ModelEndpoints : ServerBuilder() {
 *    val info = Server.database.modelInfo(
 *        ...,
 *        signals = { table ->
 *           table.postCreate { notifyCreated(it) } // invoke the launcher to launch the event
 *        }
 *    )
 *
 *    // Define an event (returns an `EventLauncher`)
 *    val notifyCreated = AppNotifications.event("Model Created", info) {
 *        ... // setup
 *    }
 * }
 * ```
 *
 * @param name The unique name for this event type
 * @param info Model information for the subject entity type
 * @param tags Optional tags for categorizing this event type
 * @param timeout Maximum execution time for event processing (default: 5 minutes)
 * @param additionalSetup DSL lambda for event setup (e.g., setting content generators or subscribers)
 * @return The created event launcher
 */
@LightningServerDsl
context(builder: ServerBuilder)
public fun <HANDLER : EventHandler, T : HasId<ID>, ID : Comparable<ID>> HANDLER.event(
    name: String,
    info: ModelInfo<*, T, ID>,
    tags: Set<String> = emptySet(),
    timeout: Duration = 5.minutes,
    additionalSetup: HANDLER.(EventDefinition<T, ID>) -> Unit
): EventLauncher<HANDLER, T, ID> {
    val type = EventDefinition(name, tags, info)

    builder.events.register(type)

    additionalSetup(type)

    val launcher = EventLauncher(type, this, timeout)

    with(builder) {
        path.path("events").path(type.name.raw.kabobCase()) include launcher
    }

    return launcher
}