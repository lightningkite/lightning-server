package com.lightningkite.ktordb

import org.litote.kmongo.serialization.*
import com.github.jershell.kbson.KBson
import com.mongodb.client.model.*
import com.mongodb.client.result.DeleteResult
import com.mongodb.client.result.InsertManyResult
import com.mongodb.client.result.InsertOneResult
import com.mongodb.client.result.UpdateResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.bson.BsonDocument
import org.litote.kmongo.MongoOperator
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.coroutine.aggregate
import org.litote.kmongo.group
import org.litote.kmongo.match
import java.util.concurrent.TimeUnit
import kotlin.reflect.KType

/**
 * MongoFieldCollection implements FieldCollection specifically for a MongoDB server.
 */
class MongoFieldCollection<Model: Any>(
    val serializer: KSerializer<Model>,
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


    @Serializable
    data class KeyHolder<Key>(val _id: Key)

    override suspend fun count(condition: Condition<Model>): Int {
        return wraps.countDocuments(condition.bson()).toInt()
    }

    override suspend fun <Key> groupCount(
        condition: Condition<Model>,
        groupBy: DataClassProperty<Model, Key>
    ): Map<Key, Int> {
        return wraps.aggregate<BsonDocument>(match(condition.bson()), group("\$" + groupBy.name, Accumulators.sum("count", 1)))
            .toList()
            .associate {
                MongoDatabase.bson.load(KeyHolder.serializer(serializer.fieldSerializer(groupBy)!!), it)._id to it.getNumber("count").intValue()
            }
    }

    private fun Aggregate.asValueBson(propertyName: String) = when(this) {
        Aggregate.Sum -> Accumulators.sum("value", "\$" + propertyName)
        Aggregate.Average -> Accumulators.avg("value", "\$" + propertyName)
        Aggregate.StandardDeviationPopulation -> Accumulators.stdDevPop("value", "\$" + propertyName)
        Aggregate.StandardDeviationSample -> Accumulators.stdDevSamp("value", "\$" + propertyName)
    }

    override suspend fun <N : Number> aggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        property: DataClassProperty<Model, N>
    ): Double? {
        return wraps.aggregate<BsonDocument>(match(condition.bson()), group(null, aggregate.asValueBson(property.name)))
            .toList()
            .map {
                if(it.isNull("value")) null
                else it.getNumber("value").doubleValue()
            }
            .firstOrNull()
    }

    override suspend fun <N: Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        groupBy: DataClassProperty<Model, Key>,
        property: DataClassProperty<Model, N>
    ): Map<Key, Double?> {
        return wraps.aggregate<BsonDocument>(match(condition.bson()), group("\$" + groupBy.name, aggregate.asValueBson(property.name)))
            .toList()
            .associate {
                MongoDatabase.bson.load(KeyHolder.serializer(serializer.fieldSerializer(groupBy)!!), it)._id to (if(it.isNull("value")) null else it.getNumber("value").doubleValue())
            }
    }
}