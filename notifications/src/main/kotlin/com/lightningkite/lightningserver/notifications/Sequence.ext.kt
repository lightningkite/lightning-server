package com.lightningkite.lightningserver.notifications

import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.database.Query
import com.lightningkite.services.database.comparator

@PublishedApi
internal fun <T> Sequence<T>.sortedWithNullable(comparator: Comparator<T>?): Sequence<T> = if (comparator == null) this else sortedWith(comparator)

public fun <T> Sequence<T>.query(query: Query<T>): Sequence<T> = this
    .filter { query.condition(it) }
    .sortedWithNullable(query.orderBy.comparator)
    .drop(query.skip)
    .take(query.limit)

public inline fun <T, V> Sequence<T>.queryBy(query: Query<V>, crossinline transform: (T) -> V): Sequence<T> = this
    .filter { query.condition(transform(it)) }
    .sortedWithNullable(
        query.orderBy.comparator?.let { cmp ->
            Comparator { a, b ->
                cmp.compare(transform(a), transform(b))
            }
        }
    )
    .drop(query.skip)
    .take(query.limit)

public fun <T> Sequence<T>.withPermissions(permissions: ModelPermissions<T>): Sequence<T> = this
    .filter { permissions.read(it) }
    .map { permissions.mask(it) }
