package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.rock.reactive.LateInitProperty
import com.lightningkite.rock.reactive.Readable
import kotlinx.serialization.KSerializer

class MockModelCollection<T : HasId<ID>, ID : Comparable<ID>>(val serializer: KSerializer<T>) : ModelCollection<T, ID> {
    val models = HashMap<ID, MockWritableModel>()

    fun populate(item: T) {
        val id = item._id
        models.getOrPut(id) { MockWritableModel(id) }.property.value = item
    }

    inner class MockWritableModel(val id: ID) : WritableModel<T> {
        override val serializer: KSerializer<T>
            get() = this@MockModelCollection.serializer
        val property = LateInitProperty<T?>()
        val value: T? get() = if (property.ready) property.value else null

        override suspend fun modify(modification: Modification<T>): T? {
            property.value = property.value?.let { modification(it) }
            actionPerformed()
            return property.value
        }

        override suspend fun delete() {
            property.unset()
            models.remove(id)
            actionPerformed()
        }

        override suspend fun invalidate() {
            actionPerformed()
        }

        override fun addListener(listener: () -> Unit): () -> Unit = property.addListener(listener)
        override suspend fun awaitRaw(): T? = property.awaitRaw()
        override suspend fun set(value: T?) {
            if(value == null) delete()
            else {
                property.set(value)
                actionPerformed()
            }
        }
    }

    private val listeners = ArrayList<() -> Unit>()
    fun addListener(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return {
            val pos = listeners.indexOfFirst { it === listener }
            if (pos != -1) {
                listeners.removeAt(pos)
            }
        }
    }

    fun actionPerformed() {
        listeners.toList().forEach { it() }
    }

    override fun get(id: ID): WritableModel<T> = models.getOrPut(id) { MockWritableModel(id) }
    override suspend fun query(query: Query<T>): Readable<List<T>> = object : Readable<List<T>> {
        override fun addListener(listener: () -> Unit): () -> Unit = this@MockModelCollection.addListener(listener)
        override suspend fun awaitRaw(): List<T> = models.values.asSequence()
            .mapNotNull { if (it.property.ready) it.property.value else null }
            .filter { query.condition(it) }
            .let {
                query.orderBy.comparator?.let { c ->
                    it.sortedWith(c)
                } ?: it.sortedBy { it._id }
            }
            .drop(query.skip)
            .take(query.limit)
            .toList()
    }

    override suspend fun watch(query: Query<T>): Readable<List<T>> = query(query)

    override suspend fun insert(item: T): WritableModel<T> {
        return models.getOrPut(item._id) { MockWritableModel(item._id) }.also { it.property.value = item }.also {
            actionPerformed()
        }
    }

    override suspend fun insert(item: List<T>): List<T> {
        return item.map { item ->
            models.getOrPut(item._id) { MockWritableModel(item._id) }.let { it.property.value = item; item }
        }.also { actionPerformed() }
    }

    override suspend fun upsert(item: T): WritableModel<T> {
        return models.getOrPut(item._id) { MockWritableModel(item._id) }.also { it.property.value = item }.also { actionPerformed() }
    }

    override suspend fun bulkModify(bulkUpdate: MassModification<T>): Int {
        var count = 0
        models.values.forEach {
            val v = it.value
            if (v != null && bulkUpdate.condition(v)) {
                it.modify(bulkUpdate.modification)
                count++
            }
        }
        actionPerformed()
        return count
    }
}