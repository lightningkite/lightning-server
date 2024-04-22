@file:UseContextualSerialization(Instant::class)

package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.tasks.startup
import com.lightningkite.lightningserver.tasks.task
import com.lightningkite.lightningserver.typed.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.serialization.UseContextualSerialization
import kotlinx.datetime.Instant
import com.lightningkite.lightningdb.SerializableProperty
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.websocket.WebSocketIdentifier
import com.lightningkite.now
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class ModelRestUpdatesWebsocket<USER: HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    path: ServerPath,
    database: () -> Database,
    info: ModelInfo<USER, T, ID>,
    key: SerializableProperty<T, *>? = null,
): ServerPathGroup(path) {
    init {
        prepareModels()
    }
    private val modelName = info.serialization.serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.')
    private val modelIdentifier = info.serialization.serializer.descriptor.serialName
    private val helper = ModelRestUpdatesWebsocketHelper[database]
    private val interfaceName = Documentable.InterfaceInfo("ModelRestEndpointsPlusUpdatesWebsocket", listOf(
        info.serialization.serializer,
        info.serialization.idSerializer
    ))

    val websocket = path.apiWebsocket<USER, Condition<T>, CollectionUpdates<T, ID>>(
        authOptions = info.authOptions,
        inputType = Condition.serializer(info.serialization.serializer),
        outputType = CollectionUpdates.serializer(info.serialization.serializer, info.serialization.idSerializer),
        belongsToInterface = interfaceName,
        summary = "Updates",
        description = "Gets updates to items in the database matching a certain condition.",
        errorCases = listOf(),
        connect = {
            val auth = this.authOrNull
            val user = auth?.serializable(now().plus(1.days))
            val collection = info.collection(this)
            helper.subscriptionDb().insertOne(
                __WebSocketDatabaseUpdatesSubscription(
                    _id = event.id,
                    databaseId = modelIdentifier,
                    condition = "",
                    user = user,
                    mask = Serialization.json.encodeToString(
                        Mask.serializer(info.serialization.serializer),
                        collection.mask()
                    ),
                    relevant = setOf(),
                    establishedAt = now()
                )
            )
        },
        message = { condition ->
            val existing = helper.subscriptionDb().get(socketId) ?: throw NotFoundException()
            @Suppress("UNCHECKED_CAST") val auth = existing.user?.real() as? RequestAuth<USER & Any>
            val p = info.collection(AuthAccessor(auth, null))
            val fullCondition = p.fullCondition(condition).simplify()
            val fullConditionSerialized = Serialization.json.encodeToString(Condition.serializer(info.serialization.serializer), fullCondition)
            helper.subscriptionDb().updateOne(
                condition = condition { it._id eq socketId },
                modification = modification {
                    it.condition assign fullConditionSerialized
                    if (key != null)
                        it.relevant assign fullCondition.relevantHashCodesForKey(key)
                    else
                        it.relevant assign null
                },
            )
        },
        disconnect = {
            helper.subscriptionDb().deleteMany(condition { it._id eq socketId })
        }
    )

    val sendWsChanges = task(
        "$modelIdentifier.sendWsUpdates",
        CollectionChanges.serializer(info.serialization.serializer)
    ) { changes: CollectionChanges<T> ->
        val jobs = ArrayList<Job>()
        val targets = if (key != null) {
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
                if (toSend.size > 50) {
                    websocket.send(it._id, CollectionUpdates(overload = true))
                } else {
                    websocket.send(it._id, CollectionUpdates(
                        updates = toSend.mapNotNull { it.new }.toSet(),
                        remove = toSend.mapNotNull { it.old.takeIf { _ -> it.new == null }?._id }.toSet()
                    ))
                }
            })
        }
        jobs.forEach { it.join() }
    }

    suspend fun sendUpdates(changes: CollectionChanges<T>) {
        changes.changes.chunked(200).forEach {
            sendWsChanges(CollectionChanges(changes = it))
        }
    }
    init {
        startup {
            info.registerChangeListener(::sendUpdates)
        }
    }
}

class ModelRestUpdatesWebsocketHelper private constructor(val database: () -> Database) {

    companion object {
        private val existing = HashMap<() -> Database, ModelRestUpdatesWebsocketHelper>()
        operator fun get(database: () -> Database) = existing.getOrPut(database) { ModelRestUpdatesWebsocketHelper(database) }
    }

    fun subscriptionDb() = database().collection<__WebSocketDatabaseUpdatesSubscription>()

    val schedule = schedule("ModelRestUpdatesWebsocketHelper.cleanup", 5.minutes) {
        val now = now()
        val db =
            subscriptionDb().deleteMany(condition<__WebSocketDatabaseUpdatesSubscription> {
                (it.condition eq "") and
                        (it.establishedAt lt now.minus(5.minutes))
            } or condition<__WebSocketDatabaseUpdatesSubscription> {
                it.establishedAt lt now.minus(1.hours)
            })

        for (changeSub in db) {
            try {
                changeSub._id.close()
            } catch (_: Exception) {
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
data class __WebSocketDatabaseUpdatesSubscription(
    override val _id: WebSocketIdentifier,
    val databaseId: String,
    val user: RequestAuthSerializable?, //USER
    val condition: String, //Condition<T>
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
        .reduceOrNull { a, b -> if (a == null || b == null) null else a.union(b) }

    is Condition.OnField<*, *> -> if (this.key == key) condition.relevantHashCodes() else null
    else -> null
}

fun <T> Condition<T>.relevantHashCodes(): Set<Int>? = when (this) {
    is Condition.And<T> -> conditions
        .asSequence()
        .mapNotNull { it.relevantHashCodes() }
        .reduceOrNull { a, b -> a.intersect(b) }

    is Condition.Or<T> -> conditions
        .asSequence()
        .map { it.relevantHashCodes() }
        .reduceOrNull { a, b -> if (a == null || b == null) null else a.union(b) }

    is Condition.Equal -> setOf(value.hashCode())
    is Condition.Inside -> values.map { it.hashCode() }.toSet()
    else -> null
}