@file:UseContextualSerialization(Instant::class)

package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.tasks.startup
import com.lightningkite.lightningserver.tasks.task
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.websocket.WebSocketIdentifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.time.Instant
import java.time.Duration
import com.lightningkite.lightningdb.SerializableProperty

@LightningServerDsl
fun <USER: HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> ServerPath.restApiWebsocket(
    database: () -> Database,
    info: ModelInfo<USER, T, ID>,
    key: SerializableProperty<T, *>? = null,
): ApiWebsocket<USER, TypedServerPath0, Query<T>, ListChange<T>> {
    prepareModels()
    val modelName = info.serialization.serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.')
    val modelIdentifier = info.serialization.serializer.descriptor.serialName
    val helper = RestApiWebsocketHelper[database]

    return apiWebsocket<USER, Query<T>, ListChange<T>>(
        authOptions = info.authOptions,
        inputType = Query.serializer(info.serialization.serializer),
        outputType = ListChange.serializer(info.serialization.serializer),
        summary = "Watch",
        description = "Gets a changing list of ${modelName}s that match the given query.",
        errorCases = listOf(),
        connect = {
            val auth = this.authOrNull
            val user = auth?.serializable(Instant.now().plus(Duration.ofDays(1)))
            val collection = info.collection(this)
            helper.subscriptionDb().insertOne(
                __WebSocketDatabaseChangeSubscription(
                    _id = event.id,
                    databaseId = modelIdentifier,
                    condition = "",
                    user = user,
                    mask = Serialization.json.encodeToString(
                        Mask.serializer(info.serialization.serializer),
                        collection.mask()
                    ),
                    relevant = setOf(),
                    establishedAt = Instant.now()
                )
            )
        },
        message = { query ->
            val existing = helper.subscriptionDb().get(socketId) ?: throw NotFoundException()
            @Suppress("UNCHECKED_CAST") val auth = existing.user?.real() as? RequestAuth<USER & Any>
            val p = info.collection(AuthAccessor(auth, null))
            val q = query.copy(condition = p.fullCondition(query.condition).simplify())
            val c = Serialization.json.encodeToString(Query.serializer(info.serialization.serializer), q)
            helper.subscriptionDb().updateOne(
                condition = condition { it._id eq socketId },
                modification = modification {
                    it.condition assign c
                    if(key != null)
                        it.relevant assign q.condition.relevantHashCodesForKey(key)
                    else
                        it.relevant assign null
                },
            )
            send(ListChange(wholeList = p.query(q).toList()))
        },
        disconnect = {
            helper.subscriptionDb().deleteMany(condition { it._id eq socketId })
        }
    ).apply {
        val sendWsChanges = task(
            "$modelIdentifier.sendWsChanges",
            CollectionChanges.serializer(info.serialization.serializer)
        ) { changes: CollectionChanges<T> ->
            val jobs = ArrayList<Job>()
            val targets = if(key != null) {
                val relevantValues = changes.changes.asSequence().flatMap { listOfNotNull(it.old, it.new) }
                    .map { key.get(it).hashCode() }
                    .toSet()
                helper.subscriptionDb().find(condition {
                    (it.databaseId eq modelIdentifier) and ((it.relevant eq null) or (it.relevant.notNull.any { it inside relevantValues }))
                })
            } else {
                helper.subscriptionDb().find(condition { it.databaseId eq modelIdentifier })
            }
            targets.collect {
                if (it.condition.isEmpty()) return@collect
                val m =
                    try {
                        Serialization.json.decodeFromString(Mask.serializer(info.serialization.serializer), it.mask)
                    } catch (e: Exception) {
                        return@collect
                    }
                val c = try {
                    Serialization.json.decodeFromString(
                        Query.serializer(info.serialization.serializer),
                        it.condition
                    )
                } catch (e: Exception) {
                    return@collect
                }
                val toSend = changes.changes.map { entry ->
                    ListChange(
                        old = entry.old?.takeIf { c.condition(it) }?.let { m(it) },
                        new = entry.new?.takeIf { c.condition(it) }?.let { m(it) },
                    )
                }.filter { it.old != null || it.new != null }

                jobs.add(launch {
                    if (toSend.size > 10) {
                        send(it._id, ListChange(wholeList = info.collection().query(c).toList()))
                    } else {
                        toSend.forEach { c ->
                            send(it._id, c)
                        }
                    }
                })
            }
            jobs.forEach { it.join() }
        }
        startup {
            info.collection().registerRawSignal { changes ->
                changes.changes.chunked(500).forEach {
                    sendWsChanges(CollectionChanges(changes = it))
                }
            }
        }
    }
}

class RestApiWebsocketHelper private constructor(val database: ()->Database) {

    companion object {
        private val existing = HashMap<()->Database, RestApiWebsocketHelper>()
        operator fun get(database: ()->Database) = existing.getOrPut(database) { RestApiWebsocketHelper(database) }
    }

    fun subscriptionDb() = database().collection<__WebSocketDatabaseChangeSubscription>()

    val schedule = schedule("WebsocketDatabaseChangeSubscriptionCleanup", Duration.ofMinutes(5)) {
        val now = Instant.now()
        val db =
            subscriptionDb().deleteMany(condition {
                it.condition eq ""
                it.establishedAt lt now.minus(Duration.ofMinutes(5))
            } or condition {
                it.establishedAt lt now.minus(Duration.ofHours(1))
            })

        for (changeSub in db) {
            try{
                changeSub._id.close()
            } catch (e: Exception) {
                // We don't really care.  We just want to shut down as many of these as we can.
                /*squish*/
            }
        }
    }
}

@Serializable
@GenerateDataClassPaths
@Suppress("ClassName")
@IndexSet(["databaseId", "relevant"])
data class __WebSocketDatabaseChangeSubscription(
    override val _id: WebSocketIdentifier,
    val databaseId: String,
    val user: RequestAuthSerializable?, //USER
    val condition: String, //Query<T>
    val mask: String, //Mask<T>
    val establishedAt: Instant,
    val relevant: Set<Int>? = null,
) : HasId<WebSocketIdentifier>


fun <T, V> Condition<T>.relevantHashCodesForKey(key: SerializableProperty<T, V>): Set<Int>? = when(this) {
    is Condition.And<T> -> conditions
        .asSequence()
        .mapNotNull { it.relevantHashCodesForKey(key) }
        .reduceOrNull { a, b -> a.intersect(b) }
    is Condition.Or<T> -> conditions
        .asSequence()
        .map { it.relevantHashCodesForKey(key) }
        .reduceOrNull { a, b -> if(a == null || b == null) null else a.union(b) }
    is Condition.OnField<*, *> -> if(this.key == key) condition.relevantHashCodes() else null
    else -> null
}
fun <T> Condition<T>.relevantHashCodes(): Set<Int>? = when(this) {
    is Condition.And<T> -> conditions
        .asSequence()
        .mapNotNull { it.relevantHashCodes() }
        .reduceOrNull { a, b -> a.intersect(b) }
    is Condition.Or<T> -> conditions
        .asSequence()
        .map { it.relevantHashCodes() }
        .reduceOrNull { a, b -> if(a == null || b == null) null else a.union(b) }
    is Condition.Equal -> setOf(value.hashCode())
    is Condition.Inside -> values.map { it.hashCode() }.toSet()
    else -> null
}