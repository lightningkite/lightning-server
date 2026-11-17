package com.lightningkite.lightningserver.notifications.events

@Deprecated("Renamed to EventDefinition", ReplaceWith("EventDefinition<EventType, T, ID>"))
public typealias TypedEventType<USER, T, ID> = EventDefinition<EventType, T, ID>

@Deprecated("Renamed to Event", ReplaceWith("Event<EventType, T, ID>"))
public typealias TypedEvent<USER, T, ID> = Event<EventType, T, ID>