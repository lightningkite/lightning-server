@file:SharedCode
package com.lightningkite.lightningdb.mock

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.lightningdb.*
import com.lightningkite.lightningdb.HasId
import io.reactivex.rxjava3.core.Single
import kotlin.math.min
import java.util.*


class MockReadModelApi<Model : HasId<UUID>>(
    val table: MockTable<Model>
) : ReadModelApi<Model>() {

    override fun list(query: Query<Model>): Single<List<Model>> =
        Single.just(
            table
                .asList()
                .filter { item -> query.condition.invoke(item) }
                .sortedWith(query.orderBy.comparator ?: compareBy { it._id })
                .drop(query.skip)
                .take(query.limit)
        )

    override fun get(id: UUID): Single<Model> = table.getItem(id)?.let {
        Single.just(it)
    } ?: Single.error(ItemNotFound("404 item with key $id not found"))
}

