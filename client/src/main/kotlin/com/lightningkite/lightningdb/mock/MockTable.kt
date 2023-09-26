@file:SharedCode
package com.lightningkite.lightningdb.mock

import com.lightningkite.khrysalis.SharedCode
import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.SignalData
import io.reactivex.rxjava3.core.Observer
import io.reactivex.rxjava3.subjects.PublishSubject
import java.util.*
import java.util.*


class MockTable<Model : HasId<UUID>> {

    val data: MutableMap<UUID, Model> = mutableMapOf()
    val signals: PublishSubject<SignalData<Model>> = PublishSubject.create()

    fun observe(condition: Condition<Model>) = signals.map { data.values.filter { condition(it) } }

    fun getItem(id: UUID): Model? = data[id]

    fun asList(): List<Model> = data.values.toList()

    fun addItem(item: Model): Model {
        data[item._id] = item
        signals.onNext(SignalData(item = item, created = true, deleted = false))
        return item
    }

    fun replaceItem(item: Model): Model {
        data[item._id] = item
        signals.onNext(SignalData(item = item, created = false, deleted = false))
        return item
    }

    fun deleteItem(item: Model) {
        deleteItemById(item._id)
    }

    fun deleteItemById(id: UUID) {
        data[id]?.let { item ->
            data.remove(id)
            signals.onNext(SignalData(item = item, created = false, deleted = true))
        }
    }
}