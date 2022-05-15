package com.lightningkite.ktordb

import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Sorts
import com.mongodb.client.result.DeleteResult
import com.mongodb.client.result.InsertManyResult
import com.mongodb.client.result.InsertOneResult
import com.mongodb.client.result.UpdateResult
import kotlinx.coroutines.flow.Flow
import org.litote.kmongo.MongoOperator
import org.litote.kmongo.coroutine.CoroutineCollection
import java.util.concurrent.TimeUnit

class MongoFieldCollection<Model: Any>(
    val wraps: CoroutineCollection<Model>
): FieldCollection<Model> {

    override suspend fun find(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long
    ): Flow<Model> {
        return wraps.find(condition.bson())
            .let {
                if (skip != 0) it.skip(skip)
                else it
            }
            .let {
                if (limit != Int.MAX_VALUE) it.limit(limit)
                else it
            }
            .maxTime(maxQueryMs, TimeUnit.MILLISECONDS)
            .let {
                if (orderBy.isEmpty())
                    it
                else
                    it.sort(Sorts.orderBy(orderBy.map {
                        if (it.ascending)
                            Sorts.ascending(it.field.name)
                        else
                            Sorts.descending(it.field.name)
                    }))
            }
            .toFlow()
    }

    override suspend fun insertOne(
        model: Model
    ): Model {
        wraps.insertOne(model)
        return model
    }

    override suspend fun insertMany(
        models: List<Model>
    ): List<Model> {
        wraps.insertMany(models)
        return models
    }

    override suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model
    ): Model? {
        val r = wraps.replaceOne(condition.bson(), model)
        if(r.matchedCount == 0L) return null
        return model
    }

    override suspend fun upsertOne(condition: Condition<Model>, model: Model): Model? {
        val r = wraps.replaceOne(condition.bson(), model, ReplaceOptions().upsert(true))
        if(r.matchedCount == 0L) return null
        return model
    }

    override suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): Boolean {
        val m = modification.bson()
        val r = wraps.updateOne(
            condition.bson(),
            m.document,
            m.options
        )
        return r.matchedCount > 0
    }

    override suspend fun findOneAndUpdate(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): EntryChange<Model> {
        val m = modification.bson()
        val before = wraps.findOneAndUpdate(
            condition.bson(),
            m.document,
            FindOneAndUpdateOptions()
                .returnDocument(ReturnDocument.BEFORE)
                .upsert(m.options.isUpsert)
                .bypassDocumentValidation(m.options.bypassDocumentValidation)
                .collation(m.options.collation)
                .arrayFilters(m.options.arrayFilters)
                .hint(m.options.hint)
                .hintString(m.options.hintString)
        ) ?: return EntryChange(null, null)
        val after = modification(before)
        return EntryChange(before, after)
    }

    override suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): Int {
        val m = modification.bson()
        return wraps.updateMany(
            condition.bson(),
            m.document,
            m.options
        ).matchedCount.toInt()
    }

    override suspend fun deleteOne(
        condition: Condition<Model>
    ): Boolean {
        return wraps.deleteOne(condition.bson()).deletedCount > 0
    }

    override suspend fun deleteMany(
        condition: Condition<Model>
    ): Int {
        return wraps.deleteMany(condition.bson()).deletedCount.toInt()
    }

    override suspend fun watch(
        condition: Condition<Model>
    ): Flow<EntryChange<Model>> = wraps.watch(condition)

}