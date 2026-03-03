package com.lightningkite.lightningserver.notifications.events

@Deprecated("Renamed to EventDefinition", ReplaceWith("EventDefinition<USER, T, ID>"))
public typealias TypedEventType<USER, T, ID> = EventDefinition<USER, T, ID>

@Deprecated("Renamed to Event", ReplaceWith("Event<USER, T, ID>"))
public typealias TypedEvent<USER, T, ID> = Event<USER, T, ID>