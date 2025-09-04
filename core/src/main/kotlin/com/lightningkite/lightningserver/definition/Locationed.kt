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