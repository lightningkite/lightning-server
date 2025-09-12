package com.lightningkite.lightningserver.definition


/**
 * Represents an item with a location.
 * */
public data class Locationed<out Location, out Item>(
    public val location: Location,
    public val item: Item
) : Map.Entry<Location, Item> {
    override val key: Location get() = location
    override val value: Item get() = item
}

public fun <L, I, R> List<Locationed<L, I>>.mapItems(transform: (I) -> R): List<Locationed<L, R>> = map { Locationed(it.location, transform(it.item)) }