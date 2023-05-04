package com.lightningkite.lightningdb

import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.mongodb.ErrorCategory
import com.mongodb.MongoBulkWriteException
import com.mongodb.MongoCommandException
import com.mongodb.MongoQueryException
import com.mongodb.client.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.bson.BsonDocument
import org.bson.conversions.Bson
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
    private val getMongo: () -> CoroutineCollection<Model>,
) : AbstractSignalFieldCollection<Model>() {
    val mongo: CoroutineCollection<Model> get() = getMongo()

    private fun sort(orderBy: List<SortPart<Model>>): Bson = Sorts.orderBy(orderBy.map {
        if (it.ascending)
            Sorts.ascending(it.field.property.name)
        else
            Sorts.descending(it.field.property.name)
    })

    private inline fun <T> exceptionWrap(operation: () -> T): T {
        try {
            return operation()
        } catch (e: MongoBulkWriteException) {
            throw BadRequestException(
                "Request had the following issues: ${
                    e.writeErrors.map {
                        ErrorCategory.fromErrorCode(
                            it.code
                        )
                    }.joinToString { it.name }
                }"
            )

        } catch (e: MongoCommandException) {
            throw BadRequestException("Request had the following issue: ${ErrorCategory.fromErrorCode(e.code)}")
        } catch (e: MongoQueryException) {
            throw BadRequestException("The request had an issue: ${e.errorMessage}")
        }
    }

    private fun handleException(e: Throwable) {
        when (e) {
            is MongoBulkWriteException -> throw BadRequestException(
                "Request had the following issues: ${
                    e.writeErrors.map {
                        ErrorCategory.fromErrorCode(
                            it.code
                        )
                    }.joinToString { it.name }
                }"
            )

            is MongoCommandException -> throw BadRequestException(
                "Request had the following issue: ${
                    ErrorCategory.fromErrorCode(
                        e.code
                    )
                }"
            )

            is MongoQueryException -> throw BadRequestException("The request had an issue: ${e.errorMessage}")
            else -> throw e
        }
    }


    override suspend fun find(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long,
    ): Flow<Model> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return emptyFlow()
        prepare.await()
        return mongo.find(cs.bson())
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
                    it.sort(sort(orderBy))
            }
            .toFlow()
            .catch { handleException(it) }
    }

    @Serializable
    data class KeyHolder<Key>(val _id: Key)

    override suspend fun count(condition: Condition<Model>): Int {
        val cs = condition.simplify()
        if (cs is Condition.Never) return 0
        prepare.await()
        return exceptionWrap { mongo.countDocuments(cs.bson()).toInt() }
    }

    override suspend fun <Key> groupCount(
        condition: Condition<Model>,
        groupBy: KProperty1<Model, Key>,
    ): Map<Key, Int> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return mapOf()
        prepare.await()
        return exceptionWrap {
            mongo.aggregate<BsonDocument>(
                match(cs.bson()),
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
        val cs = condition.simplify()
        if (cs is Condition.Never) return null
        prepare.await()
        return exceptionWrap {
            mongo.aggregate<BsonDocument>(match(cs.bson()), group(null, aggregate.asValueBson(property.name)))
                .toList()
                .map {
                    if (it.isNull("value")) null
                    else it.getNumber("value").doubleValue()
                }
                .firstOrNull()
        }
    }

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        groupBy: KProperty1<Model, Key>,
        property: KProperty1<Model, N>,
    ): Map<Key, Double?> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return mapOf()
        prepare.await()
        return exceptionWrap {
            mongo.aggregate<BsonDocument>(
                match(cs.bson()),
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
    }

    override suspend fun insertImpl(
        models: Iterable<Model>,
    ): List<Model> {
        prepare.await()
        if (models.count() == 0) return emptyList()
        val asList = models.toList()
        exceptionWrap { mongo.insertMany(asList) }
        return asList
    }

    override suspend fun replaceOneImpl(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>,
    ): EntryChange<Model> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return EntryChange(null, null)
        prepare.await()
        return updateOne(cs, Modification.Assign(model), orderBy)
    }

    override suspend fun replaceOneIgnoringResultImpl(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>,
    ): Boolean {
        val cs = condition.simplify()
        if (cs is Condition.Never) return false
        prepare.await()
        if (orderBy.isNotEmpty()) return updateOneIgnoringResultImpl(cs, Modification.Assign(model), orderBy)
        return exceptionWrap { mongo.replaceOne(cs.bson(), model).matchedCount != 0L }
    }

    override suspend fun upsertOneImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model,
    ): EntryChange<Model> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return EntryChange(null, null)
        if (modification is Modification.Chain && modification.modifications.isEmpty()) return EntryChange(null, null)
        prepare.await()
        val m = modification.simplify().bson()
        return exceptionWrap {
            if (m.upsert(model, serializer)) {
                mongo.findOneAndUpdate(
                    cs.bson(),
                    m.document,
                    FindOneAndUpdateOptions()
                        .returnDocument(ReturnDocument.BEFORE)
                        .upsert(m.options.isUpsert)
                        .bypassDocumentValidation(m.options.bypassDocumentValidation)
                        .collation(m.options.collation)
                        .arrayFilters(m.options.arrayFilters)
                        .hint(m.options.hint)
                        .hintString(m.options.hintString)
                )?.let { EntryChange(it, modification(it)) } ?: EntryChange(null, model)
            } else {
                mongo.findOneAndUpdate(
                    cs.bson(),
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
        }
    }

    override suspend fun upsertOneIgnoringResultImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model,
    ): Boolean {
        val cs = condition.simplify()
        if (cs is Condition.Never) return false
        if (modification is Modification.Chain && modification.modifications.isEmpty()) return false
        prepare.await()
        return exceptionWrap {
            val m = modification.simplify().bson()
            if (m.upsert(model, serializer)) {
                mongo.updateOne(cs.bson(), m.document, m.options).matchedCount > 0
            } else {
                if (mongo.updateOne(cs.bson(), m.document, m.options).matchedCount != 0L)
                    true
                else {
                    mongo.insertOne(model)
                    false
                }
            }
        }
    }

    override suspend fun updateOneImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>,
    ): EntryChange<Model> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return EntryChange(null, null)
        if (modification is Modification.Chain && modification.modifications.isEmpty()) return EntryChange(null, null)
        prepare.await()
        val m = modification.simplify().bson()
        val before = exceptionWrap {
            mongo.findOneAndUpdate(
                cs.bson(),
                m.document,
                FindOneAndUpdateOptions()
                    .returnDocument(ReturnDocument.BEFORE)
                    .let { if (orderBy.isEmpty()) it else it.sort(sort(orderBy)) }
                    .upsert(m.options.isUpsert)
                    .bypassDocumentValidation(m.options.bypassDocumentValidation)
                    .collation(m.options.collation)
                    .arrayFilters(m.options.arrayFilters)
                    .hint(m.options.hint)
                    .hintString(m.options.hintString)
            ) ?: return EntryChange(null, null)
        }
        val after = modification(before)
        return EntryChange(before, after)
    }

    override suspend fun updateOneIgnoringResultImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>,
    ): Boolean {
        val cs = condition.simplify()
        if (cs is Condition.Never) return false
        if (modification is Modification.Chain && modification.modifications.isEmpty()) return false
        prepare.await()
        val m = modification.simplify().bson()
        return exceptionWrap { mongo.updateOne(cs.bson(), m.document, m.options).matchedCount != 0L }
    }

    override suspend fun updateManyImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): CollectionChanges<Model> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return CollectionChanges()
        if (modification is Modification.Chain && modification.modifications.isEmpty()) return CollectionChanges()
        prepare.await()
        val m = modification.simplify().bson()
        val changes = ArrayList<EntryChange<Model>>()
        exceptionWrap {
            mongo.withDocumentClass<BsonDocument>().find(cs.bson()).toFlow().collectChunked(1000) { list ->
                mongo.updateMany(Filters.`in`("_id", list.map { it["_id"] }), m.document, m.options)
                list.asSequence().map { MongoDatabase.bson.load(serializer, it) }
                    .forEach {
                        changes.add(EntryChange(it, modification(it)))
                    }
            }
        }
        return CollectionChanges(changes = changes)
    }

    override suspend fun updateManyIgnoringResultImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): Int {
        val cs = condition.simplify()
        if (cs is Condition.Never) return 0
        if (modification is Modification.Chain && modification.modifications.isEmpty()) return 0
        prepare.await()
        val m = modification.simplify().bson()
        return exceptionWrap {
            mongo.updateMany(
                cs.bson(),
                m.document,
                m.options
            ).matchedCount.toInt()
        }
    }

    override suspend fun deleteOneImpl(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Model? {
        val cs = condition.simplify()
        if (cs is Condition.Never) return null
        prepare.await()
        return exceptionWrap {
            mongo.withDocumentClass<BsonDocument>().find(cs.bson())
                .let { if (orderBy.isEmpty()) it else it.sort(sort(orderBy)) }
                .limit(1).toFlow().firstOrNull()?.let {
                    val id = it["_id"]
                    mongo.deleteOne(Filters.eq("_id", id))
                    MongoDatabase.bson.load(serializer, it)
                }
        }
    }

    override suspend fun deleteOneIgnoringOldImpl(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>,
    ): Boolean {
        val cs = condition.simplify()
        if (cs is Condition.Never) return false
        if (orderBy.isNotEmpty()) return deleteOneImpl(condition, orderBy) != null
        prepare.await()
        return exceptionWrap { mongo.deleteOne(cs.bson()).deletedCount > 0 }
    }

    override suspend fun deleteManyImpl(condition: Condition<Model>): List<Model> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return listOf()
        prepare.await()
        val remove = ArrayList<Model>()
        exceptionWrap {
            mongo.withDocumentClass<BsonDocument>().find(cs.bson()).toFlow().collectChunked(1000) { list ->
                mongo.deleteMany(Filters.`in`("_id", list.map { it["_id"] }))
                list.asSequence().map { MongoDatabase.bson.load(serializer, it) }
                    .forEach {
                        remove.add(it)
                    }
            }
        }
        return remove
    }

    override suspend fun deleteManyIgnoringOldImpl(
        condition: Condition<Model>,
    ): Int {
        val cs = condition.simplify()
        if (cs is Condition.Never) return 0
        prepare.await()
        return exceptionWrap { mongo.deleteMany(cs.bson()).deletedCount.toInt() }
    }


    @OptIn(DelicateCoroutinesApi::class, ExperimentalSerializationApi::class)
    val prepare = GlobalScope.async(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
        val requireCompletion = ArrayList<Job>()

        serializer.descriptor.annotations.filterIsInstance<TextIndex>().firstOrNull()?.let {
            requireCompletion += launch {
                val name = "${mongo.namespace.fullName}TextIndex"
                val keys = documentOf(*it.fields.map { it to "text" }.toTypedArray())
                val options = IndexOptions().name(name)
                try {
                    mongo.createIndex(keys, options)
                } catch (e: MongoCommandException) {
                    //there is an exception if the parameters of an existing index are changed.
                    //then drop the index and create a new one
                    mongo.dropIndex(name)
                    mongo.createIndex(
                        keys,
                        options
                    )
                }
            }
        }
        serializer.descriptor.indexes().forEach {
            if (it.unique) {
                requireCompletion += launch {
                    val keys = Sorts.ascending(it.fields)
                    val options = IndexOptions().unique(true).name(it.name)
                    try {
                        mongo.ensureIndex(keys, options)
                    } catch (e: MongoCommandException) {
                        if (e.errorCode == 85) {
                            mongo.dropIndex(keys)
                            mongo.ensureIndex(keys, options)
                        }
                    }
                }
            } else {
                launch {
                    val keys = Sorts.ascending(it.fields)
                    val options = IndexOptions().unique(false).background(true).name(it.name)
                    try {
                        mongo.ensureIndex(keys, options)
                    } catch (e: MongoCommandException) {
                        if (e.errorCode == 85) {
                            mongo.dropIndex(keys)
                            mongo.ensureIndex(keys, options)
                        }
                    }
                }
            }
        }
        requireCompletion.forEach { it.join() }
    }
}
