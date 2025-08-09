package com.lightningkite.lightningserver.definition


/**
 * Represents an item with a location.
 * */
public interface Locationed<out Location, out Item> : Map.Entry<Location, Item> {
    public val location: Location
    public val item: Item

    public fun fixInPlace(): ImmutableLocation<Location, Item>

    override val key: Location get() = location
    override val value: Item get() = item
}

public data class ImmutableLocation<out Location, out Item>(
    override val location: Location,
    override val item: Item
) : Locationed<Location, Item> {
    override fun fixInPlace(): ImmutableLocation<Location, Item> = this
}

public fun <Location, Item> Locationed(location: Location, item: Item): Locationed<Location, Item> = ImmutableLocation(location, item)

public data class MutableLocation<Location, Item>(
    private val updateLocation: () -> Location,
    override val item: Item
) : Locationed<Location, Item> {
    override val location: Location get() = updateLocation()

    override fun fixInPlace(): ImmutableLocation<Location, Item> = ImmutableLocation(location, item)
}