package com.lightningkite.lightningserver.notifications

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.db.ModelInfo
import com.lightningkite.lightningserver.tasks.task
import com.lightningkite.now


/**
 * Convenience class to define an event type and create events of that type with invocations
 *
 * @param name The name of the event type. Must be unique.
 * @param info ModelInfo for the model this event is based on.
 * @param handler Your implementation of [NotificationEndpoints]
 * @param tags Used to help identify event types in the registry. Useful when you need to mark an attribute of the event type.
 *             For example, a "REQUIRED" tag could indicate that subscriptions to that event are required for every user when implementing notification permissions (Optional)
 * @param defaultSubscription Lambda that takes in a user and maybe returns a subscription to this event. Defaults to no subscription.
 * @param content Lambda to generate the content for a notification based on a given model and user
 *
 * */
class NotificationEventHandler<USER : HasId<UID>, UID : Comparable<UID>, T : HasId<ID>, ID : Comparable<ID>, CONTENT : NotificationContent>(
    val type: EventType,
    info: ModelInfo<USER, T, ID>,
    private val handler: NotificationEndpoints<USER, UID, CONTENT>,
    defaultSubscription: suspend (user: USER)->EventSubscription.Info<T>? = { null },
    content: suspend (T) -> (USER) -> CONTENT
) {
    val fullType = FullEventType(type, info, handler.eventRegistry, defaultSubscription, content)

    suspend operator fun invoke(subject: T) {
        handler.notifyEvent(
            FullEvent(time = now(), type = fullType, target = subject)
        )
    }

    val task = task("Event-${type.name.filter { !it.isWhitespace() }}-TASK", info.serialization.serializer) { invoke(it) }

    suspend fun launch(subject: T) = task(subject)
}

fun <USER : HasId<UID>, UID : Comparable<UID>, T : HasId<ID>, ID : Comparable<ID>, CONTENT : NotificationContent> NotificationEndpoints<USER, UID, CONTENT>.event(
    type: EventType,
    info: ModelInfo<USER, T, ID>,
    defaultSubscription: suspend (user: USER)->EventSubscription.Info<T>? = { null },
    content: suspend (T) -> (USER) -> CONTENT
) = NotificationEventHandler(type, info, this, defaultSubscription, content)

fun <USER : HasId<UID>, UID : Comparable<UID>, T : HasId<ID>, ID : Comparable<ID>, CONTENT : NotificationContent> NotificationEndpoints<USER, UID, CONTENT>.event(
    name: String,
    info: ModelInfo<USER, T, ID>,
    tags: Set<String> = emptySet(),
    defaultSubscription: suspend (user: USER)->EventSubscription.Info<T>? = { null },
    content: suspend (T) -> (USER) -> CONTENT
) = NotificationEventHandler(EventType(name, tags), info, this, defaultSubscription, content)

