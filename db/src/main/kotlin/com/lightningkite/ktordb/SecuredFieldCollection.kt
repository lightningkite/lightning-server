package com.lightningkite.ktordb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

open class SecuredFieldCollection<Model: Any>(
    open val wraps: FieldCollection<Model>,
    val rules: SecurityRules<Model>,
): FieldCollection<Model> {
    override suspend fun find(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long
    ): Flow<Model> {
        return wraps.find(
            condition = if(orderBy.isNotEmpty()) orderBy.map { rules.sortAllowed(it) }.reduce { left, right -> left and right } and rules.read(condition)
            else rules.read(condition),
            orderBy = orderBy,
            skip = skip,
            limit = limit,
            maxQueryMs = maxQueryMs.coerceAtMost(rules.maxQueryTimeMs())
        )
            .map { rules.mask(it) }
    }

    override suspend fun insertOne(
        model: Model
    ): Model = wraps.insertOne(rules.create(model)).let { rules.mask(it) }

    override suspend fun insertMany(
        models: List<Model>
    ): List<Model> = wraps.insertMany(models.map { rules.create(it) }).let { it.map { rules.mask(it) } }

    override suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model
    ): Model? {
        val f = rules.replace(model)
        return wraps.replaceOne(f.first and condition, f.second)?.also { rules.mask(it) }
    }

    override suspend fun upsertOne(condition: Condition<Model>, model: Model): Model? {
        val f = rules.replace(model)
        return wraps.replaceOne(f.first and condition, f.second)?.also { rules.mask(it) }
    }

    override suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): Boolean {
        val e = rules.edit(condition, modification)
        return wraps.updateOne(condition and e.first, e.second)
    }

    override suspend fun findOneAndUpdate(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): EntryChange<Model> {
        val e = rules.edit(condition, modification)
        return wraps.findOneAndUpdate(
            e.first and condition,
            modification = e.second
        ).map { rules.mask(it) }
    }

    override suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): Int {
        val e = rules.edit(condition, modification)
        return wraps.updateMany(
            condition and e.first,
            e.second
        )
    }

    override suspend fun deleteOne(
        condition: Condition<Model>
    ): Boolean {
        return wraps.deleteOne(rules.delete(condition))
    }

    override suspend fun deleteMany(
        condition: Condition<Model>
    ): Int {
        return wraps.deleteMany(rules.delete(condition))
    }

    override suspend fun watch(
        condition: Condition<Model>
    ): Flow<EntryChange<Model>> = wraps.watch(condition and rules.read(condition))
        .mapNotNull {
            val old = it.old?.let { rules.mask(it) }
            val new = it.new?.let { rules.mask(it) }
            if(old == new) null
            else EntryChange(old, new)
        }
}

