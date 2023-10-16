package com.lightningkite.lightningdb

import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.serialization.Serialization
import com.mongodb.*
import com.mongodb.client.model.*
import com.mongodb.client.model.Aggregates.group
import com.mongodb.client.model.Aggregates.match
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.bson.BsonDocument
import org.bson.conversions.Bson
import java.nio.channels.ClosedChannelException
import java.util.concurrent.TimeUnit
import kotlin.reflect.KProperty1

/**
 * MongoFieldCollection implements FieldCollection specifically for a MongoDB server.
 */
class MongoFieldCollection<Model : Any>(
    val serializer: KSerializer<Model>,
    private val getMongo: () -> MongoCollection<BsonDocument>,
    private val onConnectionError: ()->Unit,
) : AbstractSignalFieldCollection<Model>() {
    val mongo: MongoCollection<BsonDocument> get() = getMongo()

    private fun sort(orderBy: List<SortPart<Model>>, lastly: Bson? = null): Bson = Sorts.orderBy(orderBy.map {
        if (it.ascending)
            Sorts.ascending(it.field.mongo)
        else
            Sorts.descending(it.field.mongo)
    } + listOfNotNull(lastly))

    private inline fun <T> exceptionWrap(operation: () -> T): T {
        try {
            return operation()
        } catch (e: Throwable) {
            handleException(e)
        }
    }

    private fun handleException(e: Throwable): Nothing {
        when {
            e is MongoBulkWriteException && e.writeErrors.all { ErrorCategory.fromErrorCode(it.code) == ErrorCategory.DUPLICATE_KEY } -> {
                throw UniqueViolationException(cause = e, collection = mongo.namespace.collectionName)
            }

            e is MongoException && ErrorCategory.fromErrorCode(e.code) == ErrorCategory.DUPLICATE_KEY -> {
                throw UniqueViolationException(cause = e, collection = mongo.namespace.collectionName)
            }

            e is MongoSocketWriteException && e.cause is ClosedChannelException -> {
                onConnectionError()
                throw e
            }

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
        return mongo.find(cs.bson(serializer))
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
                var anyFts = false
                condition.walk { if (it is Condition.FullTextSearch) anyFts = true }
                val mts = if (anyFts) {
                    it.projection(Projections.metaTextScore("text_search_score"))
                    Sorts.metaTextScore("text_search_score")
                } else null
                it.sort(sort(orderBy, mts))
            }
            .let {
                if (orderBy.any { it.ignoreCase }) {
                    it.collation(Collation.builder().locale("en").build())
                } else it
            }
            .map { Serialization.Internal.bson.load(serializer, it) }
            .catch { handleException(it) }
    }

    @Serializable
    data class KeyHolder<Key>(val _id: Key)

    override suspend fun count(condition: Condition<Model>): Int {
        val cs = condition.simplify()
        if (cs is Condition.Never) return 0
        prepare.await()
        return exceptionWrap { mongo.countDocuments(cs.bson(serializer)).toInt() }
    }

    override suspend fun <Key> groupCount(
        condition: Condition<Model>,
        groupBy: DataClassPath<Model, Key>,
    ): Map<Key, Int> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return mapOf()
        prepare.await()
        return exceptionWrap {
            mongo.aggregate<BsonDocument>(
                listOf(
                    match(cs.bson(serializer)),
                    group("\$" + groupBy.mongo, Accumulators.sum("count", 1))
                )
            )
                .toList()
                .associate {
                    Serialization.Internal.bson.load(
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
        property: DataClassPath<Model, N>,
    ): Double? {
        val cs = condition.simplify()
        if (cs is Condition.Never) return null
        prepare.await()
        return exceptionWrap {
            mongo.aggregate(listOf(match(cs.bson(serializer)), group(null, aggregate.asValueBson(property.mongo))))
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
        groupBy: DataClassPath<Model, Key>,
        property: DataClassPath<Model, N>,
    ): Map<Key, Double?> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return mapOf()
        prepare.await()
        return exceptionWrap {
            mongo.aggregate(
                listOf(
                    match(cs.bson(serializer)),
                    group("\$" + groupBy.mongo, aggregate.asValueBson(property.mongo))
                )
            )
                .toList()
                .associate {
                    Serialization.Internal.bson.load(
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
        if (models.none()) return emptyList()
        val asList = models.toList()
        exceptionWrap { mongo.insertMany(asList.map { Serialization.Internal.bson.stringify(serializer, it) }) }
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
        return exceptionWrap {
            mongo.replaceOne(
                cs.bson(serializer),
                Serialization.Internal.bson.stringify(serializer, model)
            ).matchedCount != 0L
        }
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
        val m = modification.simplify().bson(serializer)
        return exceptionWrap {
            // TODO: Ugly hack for handling weird upserts
            if (m.upsert(model, serializer)) {
                mongo.findOneAndUpdate(
                    cs.bson(serializer),
                    m.document,
                    FindOneAndUpdateOptions()
                        .returnDocument(ReturnDocument.BEFORE)
                        .upsert(m.options.isUpsert)
                        .bypassDocumentValidation(m.options.bypassDocumentValidation)
                        .collation(m.options.collation)
                        .arrayFilters(m.options.arrayFilters)
                        .hint(m.options.hint)
                        .hintString(m.options.hintString)
                )?.let { Serialization.Internal.bson.load(serializer, it) }?.let { EntryChange(it, modification(it)) }
                    ?: EntryChange(null, model)
            } else {
                mongo.findOneAndUpdate(
                    cs.bson(serializer),
                    m.document,
                    FindOneAndUpdateOptions()
                        .returnDocument(ReturnDocument.BEFORE)
                        .upsert(m.options.isUpsert)
                        .bypassDocumentValidation(m.options.bypassDocumentValidation)
                        .collation(m.options.collation)
                        .arrayFilters(m.options.arrayFilters)
                        .hint(m.options.hint)
                        .hintString(m.options.hintString)
                )?.let { Serialization.Internal.bson.load(serializer, it) }?.let { EntryChange(it, modification(it)) }
                    ?: run { mongo.insertOne(Serialization.Internal.bson.stringify(serializer, model)); EntryChange(null, model) }
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
            val m = modification.simplify().bson(serializer)
            // TODO: Ugly hack for handling weird upserts
            if (m.upsert(model, serializer)) {
                mongo.updateOne(cs.bson(serializer), m.document, m.options).matchedCount > 0
            } else {
                if (mongo.updateOne(cs.bson(serializer), m.document, m.options).matchedCount != 0L) {
                    true
                } else {
                    mongo.insertOne(Serialization.Internal.bson.stringify(serializer, model))
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
        val m = modification.simplify().bson(serializer)
        val before = exceptionWrap {
            mongo.findOneAndUpdate(
                cs.bson(serializer),
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
        }.let { Serialization.Internal.bson.load(serializer,it) }
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
        val m = modification.simplify().bson(serializer)
        return exceptionWrap { mongo.updateOne(cs.bson(serializer), m.document, m.options).matchedCount != 0L }
    }

    override suspend fun updateManyImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): CollectionChanges<Model> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return CollectionChanges()
        if (modification is Modification.Chain && modification.modifications.isEmpty()) return CollectionChanges()
        prepare.await()
        val m = modification.simplify().bson(serializer)
        val changes = ArrayList<EntryChange<Model>>()
        // TODO: Don't love that we have to do this in chunks, but I guess we'll live.  Could this be done with pipelines?
        exceptionWrap {
            mongo.find(cs.bson(serializer)).collectChunked(1000) { list ->
                mongo.updateMany(Filters.`in`("_id", list.map { it["_id"] }), m.document, m.options)
                list.asSequence().map { Serialization.Internal.bson.load(serializer, it) }
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
        val m = modification.simplify().bson(serializer)
        return exceptionWrap {
            mongo.updateMany(
                cs.bson(serializer),
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
            // TODO: Hack, needs some retry logic at a minimum
            mongo.withDocumentClass<BsonDocument>().find(cs.bson(serializer))
                .let { if (orderBy.isEmpty()) it else it.sort(sort(orderBy)) }
                .limit(1).firstOrNull()?.let {
                    val id = it["_id"]
                    mongo.deleteOne(Filters.eq("_id", id))
                    Serialization.Internal.bson.load(serializer, it)
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
        return exceptionWrap { mongo.deleteOne(cs.bson(serializer)).deletedCount > 0 }
    }

    override suspend fun deleteManyImpl(condition: Condition<Model>): List<Model> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return listOf()
        prepare.await()
        val remove = ArrayList<Model>()
        exceptionWrap {
            // TODO: Don't love that we have to do this in chunks, but I guess we'll live.  Could this be done with pipelines?
            mongo.withDocumentClass<BsonDocument>().find(cs.bson(serializer)).collectChunked(1000) { list ->
                mongo.deleteMany(Filters.`in`("_id", list.map { it["_id"] }))
                list.asSequence().map { Serialization.Internal.bson.load(serializer, it) }
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
        return exceptionWrap { mongo.deleteMany(cs.bson(serializer)).deletedCount.toInt() }
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
                        mongo.createIndex(keys, options)
                    } catch (e: MongoCommandException) {
                        // Reform index if it already exists but with some difference in options
                        if (e.errorCode == 85) {
                            mongo.dropIndex(keys)
                            mongo.createIndex(keys, options)
                        }
                    }
                }
            } else {
                launch {
                    val keys = Sorts.ascending(it.fields)
                    val options = IndexOptions().unique(false).background(true).name(it.name)
                    try {
                        mongo.createIndex(keys, options)
                    } catch (e: MongoCommandException) {
                        // Reform index if it already exists but with some difference in options
                        if (e.errorCode == 85) {
                            mongo.dropIndex(keys)
                            mongo.createIndex(keys, options)
                        }
                    }
                }
            }
        }
        requireCompletion.forEach { it.join() }
    }
}
