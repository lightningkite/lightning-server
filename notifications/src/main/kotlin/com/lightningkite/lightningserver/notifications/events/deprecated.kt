package com.lightningkite.lightningserver.notifications.events

@Deprecated("Renamed to EventDefinition", ReplaceWith("EventDefinition<T, ID>"))
public typealias TypedEventType<USER, T, ID> = EventDefinition<T, ID>

@Deprecated("Renamed to Event", ReplaceWith("Event<T, ID>"))
public typealias TypedEvent<USER, T, ID> = Event<T, ID>