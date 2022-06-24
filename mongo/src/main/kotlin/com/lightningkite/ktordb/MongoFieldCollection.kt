package com.lightningkite.ktordb

import org.litote.kmongo.serialization.*
import com.github.jershell.kbson.KBson
import com.mongodb.client.model.*
import com.mongodb.client.result.DeleteResult
import com.mongodb.client.result.InsertManyResult
import com.mongodb.client.result.InsertOneResult
import com.mongodb.client.result.UpdateResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.serializer
import org.bson.BsonDocument
import org.bson.Document
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.coroutine.aggregate
import java.util.concurrent.TimeUnit
import kotlin.reflect.KProperty1
import kotlin.reflect.KType

/**
 * MongoFieldCollection implements FieldCollection specifically for a MongoDB server.
 */
class MongoFieldCollection<Model : Any>(
    val serializer: KSerializer<Model>,
    val wraps: CoroutineCollection<Model>
) : FieldCollection<Model> {

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
                            Sorts.ascending(it.field.property.name)
                        else
                            Sorts.descending(it.field.property.name)
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
        if (r.matchedCount == 0L) return null
        return model
    }

    override suspend fun upsertOne(condition: Condition<Model>, model: Model): Model? {
        val r = wraps.replaceOne(condition.bson(), model, ReplaceOptions().upsert(true))
        if (r.matchedCount == 0L) return null
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
        groupBy: KProperty1<Model, Key>
    ): Map<Key, Int> {
        return wraps.aggregate<BsonDocument>(
            match(condition.bson()),
            group("\$" + groupBy.name, Accumulators.sum("count", 1))
        )
            .toList()
            .associate {
                MongoDatabase.bson.load(
                    KeyHolder.serializer(serializer.fieldSerializer(groupBy)!!),
                    it
                )._id to it.getNumber("count").intValue()
            }
    }

    private fun Aggregate.asValueBson(propertyName: String) = when (this) {
        Aggregate.Sum -> Accumulators.sum("value", "\$" + propertyName)
        Aggregate.Average -> Accumulators.avg("value", "\$" + propertyName)
        Aggregate.StandardDeviationPopulation -> Accumulators.stdDevPop("value", "\$" + propertyName)
        Aggregate.StandardDeviationSample -> Accumulators.stdDevSamp("value", "\$" + propertyName)
    }

    override suspend fun <N : Number> aggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        property: KProperty1<Model, N>
    ): Double? {
        return wraps.aggregate<BsonDocument>(match(condition.bson()), group(null, aggregate.asValueBson(property.name)))
            .toList()
            .map {
                if (it.isNull("value")) null
                else it.getNumber("value").doubleValue()
            }
            .firstOrNull()
    }

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        groupBy: KProperty1<Model, Key>,
        property: KProperty1<Model, N>
    ): Map<Key, Double?> {
        return wraps.aggregate<BsonDocument>(
            match(condition.bson()),
            group("\$" + groupBy.name, aggregate.asValueBson(property.name))
        )
            .toList()
            .associate {
                MongoDatabase.bson.load(
                    KeyHolder.serializer(serializer.fieldSerializer(groupBy)!!),
                    it
                )._id to (if (it.isNull("value")) null else it.getNumber("value").doubleValue())
            }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun handleIndexes(scope: CoroutineScope) {
        val requireCompletion = ArrayList<Job>()
        fun handleDescriptor(descriptor: SerialDescriptor) {
            descriptor.annotations.forEach {
                when (it) {
                    is UniqueSet -> {
                        requireCompletion += scope.launch {
                            wraps.ensureIndex(Sorts.ascending(it.fields.toList()), IndexOptions().unique(true))
                        }
                    }
                    is IndexSet -> {
                        scope.launch {
                            wraps.ensureIndex(
                                Sorts.ascending(it.fields.toList()),
                                IndexOptions().unique(false).background(true)
                            )
                        }
                    }
                    is TextIndex -> {
                        requireCompletion += scope.launch {
                            wraps.ensureIndex(documentOf(*it.fields.map { it to "text" }.toTypedArray()), IndexOptions().name("${wraps.namespace.fullName}TextIndex"))
                        }
                    }
                    is NamedUniqueSet -> {
                        requireCompletion += scope.launch {
                            wraps.ensureIndex(
                                Sorts.ascending(it.fields.toList()),
                                IndexOptions().unique(true).name(it.indexName)
                            )
                        }
                    }
                    is NamedIndexSet -> {
                        scope.launch {
                            wraps.ensureIndex(
                                Sorts.ascending(it.fields.toList()),
                                IndexOptions().unique(false).name(it.indexName).background(true)
                            )
                        }
                    }
                    is NamedTextIndex -> {
                        requireCompletion += scope.launch {
                            wraps.ensureIndex(documentOf(*it.fields.map { it to "text" }.toTypedArray()))
                        }
                    }
                }
            }
            (0 until descriptor.elementsCount).forEach { index ->
                val sub = descriptor.getElementDescriptor(index)
                if (sub.kind == StructureKind.CLASS) handleDescriptor(sub)
                descriptor.getElementAnnotations(index).forEach {
                    when (it) {
                        is NamedIndex -> {
                            scope.launch {
                                wraps.ensureIndex(
                                    Sorts.ascending(descriptor.getElementName(index)),
                                    IndexOptions().unique(false).name(it.indexName.takeUnless { it.isBlank() })
                                        .background(true)
                                )
                            }
                        }
                        is Index -> {
                            scope.launch {
                                wraps.ensureIndex(
                                    Sorts.ascending(descriptor.getElementName(index)),
                                    IndexOptions().unique(false).background(true)
                                )
                            }
                        }
                        is NamedUnique -> {
                            requireCompletion += scope.launch {
                                wraps.ensureIndex(
                                    Sorts.ascending(descriptor.getElementName(index)),
                                    IndexOptions().unique(true).name(it.indexName.takeUnless { it.isBlank() })
                                )
                            }
                        }
                        is Unique -> {
                            requireCompletion += scope.launch {
                                wraps.ensureIndex(
                                    Sorts.ascending(descriptor.getElementName(index)),
                                    IndexOptions().unique(true)
                                )
                            }
                        }
                    }
                }
            }
        }
        handleDescriptor(serializer.descriptor)
        requireCompletion.forEach { it.join() }
    }
}