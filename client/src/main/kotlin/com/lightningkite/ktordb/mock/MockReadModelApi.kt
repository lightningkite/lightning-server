@file:SharedCode
package com.lightningkite.ktordb.mock

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.ktordb.*
import com.lightningkite.ktordb.HasId
import com.lightningkite.ktordb.UUIDFor
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

    override fun get(id: UUIDFor<Model>): Single<Model> = table.getItem(id)?.let {
        Single.just(it)
    } ?: Single.error(ItemNotFound("404 item with key $id not found"))
}

