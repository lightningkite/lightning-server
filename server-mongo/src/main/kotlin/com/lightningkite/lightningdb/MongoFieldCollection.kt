package com.lightningkite.lightningdb

import com.mongodb.client.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.*
import org.bson.BsonDocument
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.coroutine.aggregate
import org.litote.kmongo.group
import org.litote.kmongo.match
import java.util.concurrent.TimeUnit
import kotlin.reflect.KProperty1

/**
 * MongoFieldCollection implements FieldCollection specifically for a MongoDB server.
 */
class MongoFieldCollection<Model : Any>(
    val serializer: KSerializer<Model>,
    val mongo: CoroutineCollection<Model>,
) : AbstractSignalFieldCollection<Model>() {

    override suspend fun find(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long,
    ): Flow<Model> {
        prepare.await()
        return mongo.find(condition.bson())
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

    @Serializable
    data class KeyHolder<Key>(val _id: Key)

    override suspend fun count(condition: Condition<Model>): Int {
        prepare.await()
        return mongo.countDocuments(condition.bson()).toInt()
    }

    override suspend fun <Key> groupCount(
        condition: Condition<Model>,
        groupBy: KProperty1<Model, Key>,
    ): Map<Key, Int> {
        prepare.await()
        return mongo.aggregate<BsonDocument>(
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

    override suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        property: KProperty1<Model, N>,
    ): Double? {
        prepare.await()
        return mongo.aggregate<BsonDocument>(match(condition.bson()), group(null, aggregate.asValueBson(property.name)))
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
        property: KProperty1<Model, N>,
    ): Map<Key, Double?> {
        prepare.await()
        return mongo.aggregate<BsonDocument>(
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

    override suspend fun insertImpl(
        models: List<Model>,
    ): List<Model> {
        prepare.await()
        if (models.isEmpty()) return models
        mongo.insertMany(models)
        return models
    }

    override suspend fun replaceOneImpl(condition: Condition<Model>, model: Model): EntryChange<Model> {
        prepare.await()
        return updateOne(condition, Modification.Assign(model))
    }

    override suspend fun replaceOneIgnoringResultImpl(
        condition: Condition<Model>,
        model: Model,
    ): Boolean {
        prepare.await()
        return mongo.replaceOne(condition.bson(), model).matchedCount != 0L
    }

    override suspend fun upsertOneImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model,
    ): EntryChange<Model> {
        prepare.await()
        val m = modification.bson()
        return mongo.findOneAndUpdate(
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
        )?.let { EntryChange(it, modification(it)) } ?: run { mongo.insertOne(model); EntryChange(null, model) }
    }

    override suspend fun upsertOneIgnoringResultImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model,
    ): Boolean {
        prepare.await()
        if (modification is Modification.Assign && modification.value == model) {
            return mongo.replaceOne(condition.bson(), model, ReplaceOptions().upsert(true)).matchedCount != 0L
        } else {
            val m = modification.bson()
            if (mongo.updateOne(condition.bson(), m.document, m.options).matchedCount != 0L)
                return true
            else {
                mongo.insertOne(model)
                return false
            }
        }
    }

    override suspend fun updateOneImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): EntryChange<Model> {
        prepare.await()
        val m = modification.bson()
        val before = mongo.findOneAndUpdate(
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

    override suspend fun updateOneIgnoringResultImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): Boolean {
        prepare.await()
        val m = modification.bson()
        return mongo.updateOne(condition.bson(), m.document, m.options).matchedCount != 0L
    }

    override suspend fun updateManyImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): CollectionChanges<Model> {
        prepare.await()
        val m = modification.bson()
        val changes = ArrayList<EntryChange<Model>>()
        mongo.withDocumentClass<BsonDocument>().find(condition.bson()).toFlow().collectChunked(1000) { list ->
            mongo.updateMany(Filters.`in`("_id", list.map { it["_id"] }), m.document, m.options)
            list.asSequence().map { MongoDatabase.bson.load(serializer, it) }
                .forEach {
                    changes.add(EntryChange(it, modification(it)))
                }
        }
        return CollectionChanges(changes = changes)
    }

    override suspend fun updateManyIgnoringResultImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): Int {
        prepare.await()
        val m = modification.bson()
        return mongo.updateMany(
            condition.bson(),
            m.document,
            m.options
        ).matchedCount.toInt()
    }

    override suspend fun deleteOneImpl(condition: Condition<Model>): Model? {
        prepare.await()
        return mongo.withDocumentClass<BsonDocument>().find(condition.bson()).toFlow().firstOrNull()?.let {
            val id = it["_id"]
            mongo.deleteOne(Filters.eq("_id", id))
            MongoDatabase.bson.load(serializer, it)
        }
    }

    override suspend fun deleteOneIgnoringOldImpl(
        condition: Condition<Model>,
    ): Boolean {
        prepare.await()
        return mongo.deleteOne(condition.bson()).deletedCount > 0
    }

    override suspend fun deleteManyImpl(condition: Condition<Model>): List<Model> {
        prepare.await()
        val remove = ArrayList<Model>()
        mongo.withDocumentClass<BsonDocument>().find(condition.bson()).toFlow().collectChunked(1000) { list ->
            mongo.deleteMany(Filters.`in`("_id", list.map { it["_id"] }))
            list.asSequence().map { MongoDatabase.bson.load(serializer, it) }
                .forEach {
                    remove.add(it)
                }
        }
        return remove
    }

    override suspend fun deleteManyIgnoringOldImpl(
        condition: Condition<Model>,
    ): Int {
        prepare.await()
        return mongo.deleteMany(condition.bson()).deletedCount.toInt()
    }


    @OptIn(DelicateCoroutinesApi::class, ExperimentalSerializationApi::class)
    val prepare = GlobalScope.async(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
        val requireCompletion = ArrayList<Job>()
        val seen = HashSet<SerialDescriptor>()
        fun handleDescriptor(descriptor: SerialDescriptor) {
            if (!seen.add(descriptor)) return
            descriptor.annotations.forEach {
                when (it) {
                    is UniqueSet -> {
                        requireCompletion += launch {
                            mongo.ensureIndex(
                                Sorts.ascending(it.fields.toList()),
                                IndexOptions().unique(true).partialFilterExpression(
                                    documentOf(*it.fields.map { it to documentOf("\$type" to descriptor.getElementDescriptor(descriptor.getElementIndex(it)).bsonType().value) }.toTypedArray())
                                )
                            )
                        }
                    }

                    is IndexSet -> {
                        launch {
                            mongo.ensureIndex(
                                Sorts.ascending(it.fields.toList()),
                                IndexOptions().unique(false).background(true)
                            )
                        }
                    }

                    is TextIndex -> {
                        requireCompletion += launch {
                            mongo.ensureIndex(
                                documentOf(*it.fields.map { it to "text" }.toTypedArray()),
                                IndexOptions().name("${mongo.namespace.fullName}TextIndex")
                            )
                        }
                    }

                    is NamedUniqueSet -> {
                        requireCompletion += launch {
                            mongo.ensureIndex(
                                Sorts.ascending(it.fields.toList()),
                                IndexOptions().unique(true).name(it.indexName).partialFilterExpression(
                                    documentOf(*it.fields.map { it to documentOf("\$type" to descriptor.getElementDescriptor(descriptor.getElementIndex(it)).bsonType().value) }.toTypedArray())
                                )
                            )
                        }
                    }

                    is NamedIndexSet -> {
                        launch {
                            mongo.ensureIndex(
                                Sorts.ascending(it.fields.toList()),
                                IndexOptions().unique(false).name(it.indexName).background(true)
                            )
                        }
                    }

                    is NamedTextIndex -> {
                        requireCompletion += launch {
                            mongo.ensureIndex(documentOf(*it.fields.map { it to "text" }.toTypedArray()))
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
                            launch {
                                mongo.ensureIndex(
                                    Sorts.ascending(descriptor.getElementName(index)),
                                    IndexOptions().unique(false).name(it.indexName.takeUnless { it.isBlank() })
                                        .background(true)
                                )
                            }
                        }

                        is Index -> {
                            launch {
                                mongo.ensureIndex(
                                    Sorts.ascending(descriptor.getElementName(index)),
                                    IndexOptions().unique(false).background(true)
                                )
                            }
                        }

                        is NamedUnique -> {
                            requireCompletion += launch {
                                mongo.ensureIndex(
                                    Sorts.ascending(descriptor.getElementName(index)),
                                    IndexOptions().unique(true).name(it.indexName.takeUnless { it.isBlank() })
                                        .partialFilterExpression(
                                            documentOf(descriptor.getElementName(index) to documentOf("\$type" to descriptor.getElementDescriptor(index).bsonType().value))
                                        )
                                )
                            }
                        }

                        is Unique -> {
                            requireCompletion += launch {
                                mongo.ensureIndex(
                                    Sorts.ascending(descriptor.getElementName(index)),
                                    IndexOptions().unique(true).partialFilterExpression(
                                        documentOf(descriptor.getElementName(index) to documentOf("\$type" to descriptor.getElementDescriptor(index).bsonType().value))
                                    )
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

private suspend fun <Model> Flow<Model>.collectChunked(chunkSize: Int, action: suspend (List<Model>) -> Unit) {
    val list = ArrayList<Model>()
    this.collect {
        list.add(it)
        if (list.size >= chunkSize) {
            action(list)
            list.clear()
        }
    }
    action(list)
}