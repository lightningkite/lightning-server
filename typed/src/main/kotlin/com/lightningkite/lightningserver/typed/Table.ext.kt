package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.CollectionChanges
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.EntryChange
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.SortPart
import com.lightningkite.services.database.Table

public context(server: ServerRuntime)
fun <Model : HasId<ID>, ID : Comparable<ID>> Table<Model>.withServerRuntimeChangeListeners(
    changeListeners: List<suspend context(ServerRuntime) (CollectionChanges<Model>) -> Unit>
): Table<Model> = object : Table<Model> by this@withServerRuntimeChangeListeners {
    override val wraps = this@withServerRuntimeChangeListeners

    suspend fun changed(changes: List<EntryChange<Model>>) {
        val changeSet = CollectionChanges(changes)
        changeListeners.forEach { it.invoke(server, changeSet) }
    }

    override suspend fun insert(models: Iterable<Model>): List<Model> = wraps.insert(models)
        .also { changed(it.map { EntryChange(null, it) }) }

    override suspend fun deleteMany(condition: Condition<Model>): List<Model> = wraps.deleteMany(condition)
        .also { changed(it.map { EntryChange(it, null) }) }

    override suspend fun deleteOne(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Model? =
        wraps.deleteOne(condition, orderBy)
            .also { changed(listOf(EntryChange(it, null))) }

    override suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> = wraps.replaceOne(condition, model, orderBy)
        .also { changed(listOf(it)) }

    override suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> = wraps.updateOne(condition, modification, orderBy)
        .also { changed(listOf(it)) }

    override suspend fun upsertOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): EntryChange<Model> = wraps.upsertOne(condition, modification, model)
        .also { changed(listOf(it)) }

    override suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): CollectionChanges<Model> = wraps.updateMany(condition, modification)
        .also { changed(it.changes) }


    override suspend fun replaceOneIgnoringResult(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): Boolean =
        if (changeListeners.isEmpty()) wraps.replaceOneIgnoringResult(condition, model, orderBy) else replaceOne(
            condition,
            model,
            orderBy
        ).new != null

    override suspend fun upsertOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): Boolean =
        if (changeListeners.isEmpty()) wraps.upsertOneIgnoringResult(condition, modification, model) else upsertOne(
            condition,
            modification,
            model
        ).old != null

    override suspend fun updateOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): Boolean =
        if (changeListeners.isEmpty()) wraps.updateOneIgnoringResult(condition, modification, orderBy) else updateOne(
            condition,
            modification,
            orderBy
        ).new != null

    override suspend fun updateManyIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): Int = if (changeListeners.isEmpty()) wraps.updateManyIgnoringResult(
        condition,
        modification
    ) else updateMany(condition, modification).changes.size

    override suspend fun deleteOneIgnoringOld(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>
    ): Boolean = if (changeListeners.isEmpty()) wraps.deleteOneIgnoringOld(condition, orderBy) else deleteOne(
        condition,
        orderBy
    ) != null

    override suspend fun deleteManyIgnoringOld(condition: Condition<Model>): Int =
        if (changeListeners.isEmpty()) wraps.deleteManyIgnoringOld(condition) else deleteMany(condition).size

}