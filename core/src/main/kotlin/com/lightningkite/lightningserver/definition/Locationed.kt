package com.lightningkite.lightningserver.definition


/**
 * Associates an item with a location, implementing [Map.Entry] for convenience.
 *
 * This is primarily used in [ServerDefinition] to track the path location where modules and
 * endpoints are registered. It provides a clean way to pair a location (such as a path) with
 * an associated item (such as a handler or module).
 *
 * @param Location The type of the location (e.g., [com.lightningkite.lightningserver.pathing.PathSpec0])
 * @param Item The type of the item being located
 * @property location The location where the item is registered
 * @property item The item being located
 */
public data class Locationed<out Location, out Item>(
    public val location: Location,
    public val item: Item
) : Map.Entry<Location, Item> {
    override val key: Location get() = location
    override val value: Item get() = item
}

/**
 * Transforms the items in a list of [Locationed] while preserving their locations.
 *
 * This is useful when you need to apply a transformation to items without changing
 * their associated locations, such as when building or flattening module hierarchies.
 *
 * @param transform A function that transforms each item
 * @return A new list with transformed items at the same locations
 */
public fun <L, I, R> List<Locationed<L, I>>.mapItems(transform: (I) -> R): List<Locationed<L, R>> = map { Locationed(it.location, transform(it.item)) }