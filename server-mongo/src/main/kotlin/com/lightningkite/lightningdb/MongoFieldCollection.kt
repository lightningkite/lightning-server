package com.lightningkite.lightningdb

import com.lightningkite.GeoCoordinateGeoJsonSerializer
import com.lightningkite.lightningserver.exceptions.report
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.serialization.DataClassPath
import com.mongodb.MongoCommandException
import com.mongodb.client.model.*
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.elementDescriptors
import org.bson.BsonDocument
import org.bson.conversions.Bson
import java.util.concurrent.TimeUnit

class MongoFieldCollection<Model : Any>(
    override val serializer: KSerializer<Model>,
    private val access: MongoCollectionAccess,
) : FieldCollection<Model> {

    private suspend inline fun <T> access(crossinline action: suspend MongoCollection<BsonDocument>.() -> T): T {
        return access.run {
            prepare()
            action()
        }
    }

    override suspend fun insert(models: Iterable<Model>): List<Model> = access {
        if (models.none()) return@access emptyList()
        val asList = models.toList()
        insertMany(asList.map { Serialization.Internal.bson.stringify(serializer, it) })
        return@access asList
    }

    override suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> = updateOne(condition, Modification.Assign(model), orderBy)

    override suspend fun replaceOneIgnoringResult(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): Boolean {
        val cs = condition.simplify()
        if (cs is Condition.Never) return false
        if (orderBy.isNotEmpty()) return updateOneIgnoringResult(cs, Modification.Assign(model), orderBy)
        return access {
            replaceOne(
                cs.bson(serializer),
                Serialization.Internal.bson.stringify(serializer, model)
            ).matchedCount != 0L
        }
    }

    override suspend fun upsertOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): EntryChange<Model> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return EntryChange(null, null)
        val simplifiedModification = modification.simplify()
        if (simplifiedModification.isNothing) return EntryChange(null, null)
        val m = simplifiedModification.bson(serializer)
        return access {
            // TODO: Ugly hack for handling weird upserts
            if (m.upsert(model, serializer)) {
                findOneAndUpdate(
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
                findOneAndUpdate(
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
                    ?: run {
                        insertOne(Serialization.Internal.bson.stringify(serializer, model)); EntryChange(
                        null,
                        model
                    )
                    }
            }
        }
    }

    override suspend fun upsertOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): Boolean {
        val cs = condition.simplify()
        if (cs is Condition.Never) return false
        val simplifiedModification = modification.simplify()
        if (simplifiedModification.isNothing) return false
        val m = simplifiedModification.bson(serializer)
        return access {
            // TODO: Ugly hack for handling weird upserts
            if (m.upsert(model, serializer)) {
                updateOne(cs.bson(serializer), m.document, m.options).matchedCount > 0
            } else {
                if (updateOne(cs.bson(serializer), m.document, m.options).matchedCount != 0L) {
                    true
                } else {
                    insertOne(Serialization.Internal.bson.stringify(serializer, model))
                    false
                }
            }
        }
    }

    override suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return EntryChange(null, null)
        val simplifiedModification = modification.simplify()
        if (simplifiedModification.isNothing) return EntryChange(null, null)
        val m = simplifiedModification.bson(serializer)
        val before = access<Model?> {
            findOneAndUpdate(
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
            )?.let { Serialization.Internal.bson.load(serializer, it) }
        } ?: return EntryChange(null, null)
        val after = modification(before)
        return EntryChange(before, after)
    }

    override suspend fun updateOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): Boolean {
        val cs = condition.simplify()
        if (cs is Condition.Never) return false
        val simplifiedModification = modification.simplify()
        if (simplifiedModification.isNothing) return false
        val m = simplifiedModification.bson(serializer)
        return access { updateOne(cs.bson(serializer), m.document, m.options).matchedCount != 0L }
    }

    override suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): CollectionChanges<Model> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return CollectionChanges()
        val simplifiedModification = modification.simplify()
        if (simplifiedModification.isNothing) return CollectionChanges()
        val m = simplifiedModification.bson(serializer)
        val changes = ArrayList<EntryChange<Model>>()
        // TODO: Don't love that we have to do this in chunks, but I guess we'll live.  Could this be done with pipelines?
        access {
            find(cs.bson(serializer)).collectChunked(1000) { list ->
                updateMany(Filters.`in`("_id", list.map { it["_id"] }), m.document, m.options)
                list.asSequence().map { Serialization.Internal.bson.load(serializer, it) }
                    .forEach {
                        changes.add(EntryChange(it, modification(it)))
                    }
            }
        }
        return CollectionChanges(changes = changes)
    }

    override suspend fun updateManyIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): Int {
        val cs = condition.simplify()
        if (cs is Condition.Never) return 0
        val simplifiedModification = modification.simplify()
        if (simplifiedModification.isNothing) return 0
        val m = simplifiedModification.bson(serializer)
        return access {
            updateMany(
                cs.bson(serializer),
                m.document,
                m.options
            ).matchedCount.toInt()
        }
    }

    override suspend fun deleteOne(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Model? {
        val cs = condition.simplify()
        if (cs is Condition.Never) return null
        return access {
            // TODO: Hack, needs some retry logic at a minimum
            withDocumentClass<BsonDocument>().find(cs.bson(serializer))
                .let { if (orderBy.isEmpty()) it else it.sort(sort(orderBy)) }
                .limit(1).firstOrNull()?.let {
                    val id = it["_id"]
                    deleteOne(Filters.eq("_id", id))
                    Serialization.Internal.bson.load(serializer, it)
                }
        }
    }

    override suspend fun deleteOneIgnoringOld(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>
    ): Boolean {
        val cs = condition.simplify()
        if (cs is Condition.Never) return false
        if (orderBy.isNotEmpty()) return deleteOne(condition, orderBy) != null
        return access { deleteOne(cs.bson(serializer)).deletedCount > 0 }
    }

    override suspend fun deleteMany(condition: Condition<Model>): List<Model> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return listOf()
        val remove = ArrayList<Model>()
        access {
            // TODO: Don't love that we have to do this in chunks, but I guess we'll live.  Could this be done with pipelines?
            withDocumentClass<BsonDocument>().find(cs.bson(serializer)).collectChunked(1000) { list ->
                deleteMany(Filters.`in`("_id", list.map { it["_id"] }))
                list.asSequence().map { Serialization.Internal.bson.load(serializer, it) }
                    .forEach {
                        remove.add(it)
                    }
            }
        }
        return remove
    }

    override suspend fun deleteManyIgnoringOld(condition: Condition<Model>): Int {
        val cs = condition.simplify()
        if (cs is Condition.Never) return 0
        return access { deleteMany(cs.bson(serializer)).deletedCount.toInt() }
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
        return access {
            find(cs.bson(serializer))
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
        }
    }

    @Serializable
    data class KeyHolder<Key>(val _id: Key)

    override suspend fun count(condition: Condition<Model>): Int {
        val cs = condition.simplify()
        if (cs is Condition.Never) return 0
        return access { countDocuments(cs.bson(serializer)).toInt() }
    }

    override suspend fun <Key> groupCount(
        condition: Condition<Model>,
        groupBy: DataClassPath<Model, Key>,
    ): Map<Key, Int> {
        val cs = condition.simplify()
        if (cs is Condition.Never) return mapOf()
        return access {
            aggregate<BsonDocument>(
                listOf(
                    Aggregates.match(cs.bson(serializer)),
                    Aggregates.group("\$" + groupBy.mongo, Accumulators.sum("count", 1))
                )
            )
                .toList()
                .associate {
                    Serialization.Internal.bson.load(
                        KeyHolder.serializer(groupBy.serializer),
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
        return access {
            aggregate(
                listOf(
                    Aggregates.match(cs.bson(serializer)),
                    Aggregates.group(null, aggregate.asValueBson(property.mongo))
                )
            )
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
        return access {
            aggregate(
                listOf(
                    Aggregates.match(cs.bson(serializer)),
                    Aggregates.group("\$" + groupBy.mongo, aggregate.asValueBson(property.mongo))
                )
            )
                .toList()
                .associate {
                    Serialization.Internal.bson.load(
                        KeyHolder.serializer(groupBy.serializer),
                        it
                    )._id to (if (it.isNull("value")) null else it.getNumber("value").doubleValue())
                }
        }
    }

    private var preparedAlready = false

    @OptIn(DelicateCoroutinesApi::class, ExperimentalSerializationApi::class)
    private suspend fun MongoCollection<BsonDocument>.prepare() {
        if (preparedAlready) return
        coroutineScope {
            val requireCompletion = ArrayList<Job>()

            serializer.descriptor.annotations.filterIsInstance<TextIndex>().firstOrNull()?.let {
                requireCompletion += launch {
                    val name = "${namespace.fullName}TextIndex"
                    val keys = documentOf(*it.fields.map { it to "text" }.toTypedArray())
                    val options = IndexOptions().name(name)
                    try {
                        createIndex(keys, options)
                    } catch (e: MongoCommandException) {
                        if(e.errorCode == 85) {
                            //there is an exception if the parameters of an existing index are changed.
                            //then drop the index and create a new one
                            try {
                                dropIndex(name)
                                createIndex(
                                    keys,
                                    options
                                )
                            } catch (e2: MongoCommandException) {
                                Exception(
                                    "Creating text index failed on ${this@prepare.namespace.fullName}",
                                    e
                                ).report()
                                Exception(
                                    "Creating text index failed on ${this@prepare.namespace.fullName} even after attempted removal",
                                    e2
                                ).report()
                            }
                        } else {
                            e.report()
                        }
                    }
                }
            }
            serializer.descriptor.indexes().forEach {
                if (it.type == GeoCoordinateGeoJsonSerializer.descriptor.serialName) {
                    requireCompletion += launch {
                        val nameOrDefault = it.name ?: it.fields[0].plus("_geo")
                        try {
                            createIndex(Indexes.geo2dsphere(it.fields), IndexOptions().name(nameOrDefault))
                        } catch (e: MongoCommandException) {
                            // Reform index if it already exists but with some difference in options
                            if (e.errorCode == 85) {
                                try {
                                    dropIndex(nameOrDefault)
                                    createIndex(Indexes.geo2dsphere(it.fields), IndexOptions().name(nameOrDefault))
                                } catch (e2: MongoCommandException) {
                                    Exception(
                                        "Creating geo index failed on ${this@prepare.namespace.fullName}",
                                        e
                                    ).report()
                                    Exception(
                                        "Creating geo index failed on ${this@prepare.namespace.fullName} even after attempted removal",
                                        e2
                                    ).report()
                                }
                            } else {
                                e.report()
                            }
                        }
                    }
                } else if (it.unique) {
                    requireCompletion += launch {
                        val keys = Sorts.ascending(it.fields)
                        val options = IndexOptions().unique(true).name(it.name)
                        try {
                            createIndex(keys, options)
                        } catch (e: MongoCommandException) {
                            // Reform index if it already exists but with some difference in options
                            if (e.errorCode == 85) {
                                try {
                                    dropIndex(keys)
                                    createIndex(keys, options)
                                } catch (e2: MongoCommandException) {
                                    Exception(
                                        "Creating unique index failed on ${this@prepare.namespace.fullName}",
                                        e
                                    ).report()
                                    Exception(
                                        "Creating unique index failed on ${this@prepare.namespace.fullName} even after attempted removal",
                                        e2
                                    ).report()
                                }
                            } else {
                                e.report()
                            }
                        }
                    }
                } else {
                    launch {
                        val keys = Sorts.ascending(it.fields)
                        val options = IndexOptions().unique(false).background(true).name(it.name)
                        try {
                            createIndex(keys, options)
                        } catch (e: MongoCommandException) {
                            // Reform index if it already exists but with some difference in options
                            if (e.errorCode == 85) {
                                try {
                                    dropIndex(keys)
                                    createIndex(keys, options)
                                } catch (e2: MongoCommandException) {
                                    Exception("Creating index failed on ${this@prepare.namespace.fullName}", e).report()
                                    Exception(
                                        "Creating index failed on ${this@prepare.namespace.fullName} even after attempted removal",
                                        e2
                                    ).report()
                                }
                            } else {
                                e.report()
                            }
                        }
                    }
                }
            }
            requireCompletion.forEach { it.join() }
        }
        preparedAlready = true
    }

    private fun sort(orderBy: List<SortPart<Model>>, lastly: Bson? = null): Bson = Sorts.orderBy(orderBy.map {
        if (it.ascending)
            Sorts.ascending(it.field.mongo)
        else
            Sorts.descending(it.field.mongo)
    } + listOfNotNull(lastly))
}