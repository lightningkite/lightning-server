@file:SharedCode
package com.lightningkite.lightningdb.mock

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.lightningdb.*
import io.reactivex.rxjava3.core.Single
import java.util.*

class MockWriteModelApi<Model : HasId<UUID>>(
    val table: MockTable<Model>,
) : WriteModelApi<Model>() {

    override fun post(value: Model): Single<Model> =
        Single.just(table.addItem(value))

    override fun postBulk(values: List<Model>): Single<List<Model>> =
        Single.just(values.map { table.addItem(it) })

    override fun upsert(value: Model, id: UUIDFor<Model>): Single<Model> =
        Single.just(table.addItem(value))

    override fun put(value: Model): Single<Model> =
        Single.just(table.replaceItem(value))

    override fun putBulk(values: List<Model>): Single<List<Model>> =
        Single.just(values.map { table.replaceItem(it) })

    override fun patch(id: UUIDFor<Model>, modification: Modification<Model>): Single<Model> =
        table.data[id]?.let { item ->
            val modified = modification.invoke(item)
            table.replaceItem(modified)
            Single.just(modified)
        } ?: Single.error(ItemNotFound("404 item with key $id not found"))

    override fun patchBulk(modification: MassModification<Model>): Single<Long> =
        Single.just(table
            .asList()
            .filter { modification.condition.invoke(it) }
            .map { table.replaceItem(modification.modification.invoke(it)) })
            .map { it.size.toLong() }

    override fun delete(id: UUIDFor<Model>): Single<Unit> = Single.just(table.deleteItemById(id))

    override fun deleteBulk(condition: Condition<Model>): Single<Unit> = Single.just(table
        .asList()
        .filter { condition.invoke(it) }
        .forEach { table.deleteItem(it) })
}