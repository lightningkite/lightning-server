package com.lightningkite.ktordb

import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.changestream.OperationType
import com.mongodb.reactivestreams.client.MongoCollection
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import org.bson.BsonBinarySubType
import org.bson.BsonType
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.coroutine.coroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.collections.HashMap

private val activeMultiplex = ConcurrentHashMap<MongoCollection<*>, Flow<EntryChange<*>>>()
private class PrecomputedHash<T>(val item: T, val hash: Int) {
    override fun hashCode(): Int {
        return hash
    }

    override fun equals(other: Any?): Boolean {
        return other is PrecomputedHash<*> && item == other.item
    }
}
fun <T: Any> CoroutineCollection<T>.watchMultiplex(): Flow<EntryChange<T>> {
    val cache = collection.cache()
    val registry = collection.codecRegistry
    val codec = registry.get(documentClass)
    return activeMultiplex.getOrPut(this.collection) {
        collection.watch(
            listOf(
                Aggregates.match(
                    Filters.`in`(
                        "operationType",
                        OperationType.INSERT.value,
                        OperationType.UPDATE.value,
                        OperationType.REPLACE.value,
                        OperationType.DELETE.value,
                    )
                )
            )
        ).coroutine
            .toFlow()
//            .onStart { println("Started listening") }
//            .onCompletion { println("Stopped listening") }
            .mapNotNull {
                val rawKey = it.documentKey?.get("_id") ?: throw IllegalStateException("Raw key missing. Got ${it}")
                val key: Any = when(rawKey.bsonType) {
                    BsonType.DOUBLE -> rawKey.asDouble().value
                    BsonType.STRING -> rawKey.asString().value
                    BsonType.BINARY -> when(rawKey.asBinary().type) {
                        BsonBinarySubType.UUID_LEGACY.value,
                        BsonBinarySubType.UUID_STANDARD.value -> rawKey.asBinary().asUuid()
                        else -> rawKey.asBinary().data.let { PrecomputedHash(it, it.contentHashCode()) }
                    }
                    BsonType.OBJECT_ID -> rawKey.asObjectId().value
                    BsonType.BOOLEAN -> rawKey.asBoolean().value
                    BsonType.DATE_TIME -> rawKey.asDateTime().value
                    BsonType.SYMBOL -> rawKey.asSymbol().symbol
                    BsonType.INT32 -> rawKey.asInt32().value
                    BsonType.TIMESTAMP -> rawKey.asTimestamp().value
                    BsonType.INT64 -> rawKey.asInt64().value
                    BsonType.DECIMAL128 -> rawKey.asDecimal128().value
                    else -> throw IllegalStateException("Key type of ${rawKey.bsonType} not supported")
                }
                val old = cache[key]
                when(it.operationType) {
                    OperationType.DELETE ->  {
                        cache.remove(key)
                        EntryChange(old = old, null)
                    }
                    OperationType.INSERT -> {
                        val new = codec.fromDocument(it.fullDocument ?: throw IllegalStateException("Inserted document missing. Got ${it}"), registry)
                        cache[key] = new
                        EntryChange(null, new)
                    }
                    OperationType.UPDATE -> {
                        val oldNN = old ?: findOneById(key) ?: return@mapNotNull null
                        val new = codec.fromUpdateDescription(oldNN, it.updateDescription ?: throw IllegalStateException("Inserted document missing. Got ${it}"))
                        cache[key] = new
                        EntryChange(oldNN, new)
                    }
                    OperationType.REPLACE -> {
                        val oldNN = old ?: findOneById(key)
                        val new = codec.fromDocument(it.fullDocument ?: throw IllegalStateException("Inserted document missing. Got ${it}"), registry)
                        cache[key] = new
                        EntryChange(oldNN, new)
                    }
                    else -> throw Error()
                }
            }
            .retry {
                System.err.println("While processing change stream:")
                it.printStackTrace()
                delay(1000L)
                true
            }
            .shareIn(GlobalScope, SharingStarted.WhileSubscribed(1000L))
    } as Flow<EntryChange<T>>
}

private val watchedDataCache = HashMap<MongoCollection<*>, ConcurrentHashMap<Any, Any>>()
fun <T: Any> MongoCollection<T>.cache() = watchedDataCache.getOrPut(this) { ConcurrentHashMap() } as ConcurrentHashMap<Any, T>
private val watchedDataInteresting = HashMap<MongoCollection<*>, ConcurrentLinkedDeque<Condition<*>>>()
fun <T: Any> MongoCollection<T>.startInterest(filter: Condition<T>){ watchedDataInteresting.getOrPut(this) { ConcurrentLinkedDeque() }.add(filter)}
fun <T: Any> MongoCollection<T>.stopInterest(filter: Condition<T>){ watchedDataInteresting.getOrPut(this) { ConcurrentLinkedDeque() }.remove(filter)}
inline fun <T: Any> MongoCollection<T>.interestedIn(filter: Condition<T>, crossinline block: ()->Unit) {
    startInterest(filter)
    block()
    stopInterest(filter)
}
fun cleanMultiplexCache() {
    for((key, value) in watchedDataCache) {
        val interesting = (watchedDataInteresting[key] ?: listOf()) as Collection<Condition<Any>>
        for((subKey, subValue) in value) {
            if(interesting.none { it(subValue) }) {
                value.remove(subKey)
            }
        }
    }
}

fun <T: HasId> MongoCollection<T>.insertIntoCache(item: T) {
    cache()[item._id] = item
}
fun <T: HasId> MongoCollection<T>.insertIntoCache(items: List<T>) = items.forEach { insertIntoCache(it) }

private fun <T> EntryChange<T>.given(condition: Condition<T>): EntryChange<T>? {
    val old2 = old?.takeIf { condition(it) }
    val new2 = new?.takeIf { condition(it) }
    return if(old2 == null && new2 == null) null
    else EntryChange(old2, new2)
}

fun <T: Any> CoroutineCollection<T>.watch(condition: Condition<T>): Flow<EntryChange<T>> {
    return watchMultiplex()
        .mapNotNull { it.given(condition) }
        .onStart { collection.startInterest(condition) }
        .onCompletion { collection.stopInterest(condition) }
}
