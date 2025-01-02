@file:UseContextualSerialization(Instant::class)

package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.serverLogger
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.TypeRetriever
import com.lightningkite.lightningserver.typed.*
import kotlinx.serialization.UseContextualSerialization
import kotlinx.datetime.Instant
import com.lightningkite.serialization.SerializableProperty
import com.lightningkite.lightningserver.websocket.WebSocketConnectRequest
import com.lightningkite.lightningserver.websocket.WebSocketTopic
import com.lightningkite.now
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.collections.map
import kotlin.time.Duration.Companion.days


// Condition<T>, CollectionUpdates<T, ID>
@Serializable
data class ModelRestUpdatesWebsocketData<T : HasId<ID>, ID : Comparable<ID>>(
    val user: RequestAuthSerializable?, //USER
    val condition: Condition<T> = Condition.Never,
    val mask: Mask<T>,
    val topics: Set<String> = setOf(),
) {
    @Suppress("UNCHECKED_CAST")
    fun <USER : HasId<*>?> auth() = user?.real() as? RequestAuth<USER & Any>
}

class ModelRestUpdatesWebsocket<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    path: TypedServerPath0,
    val info: ModelInfo<USER, T, ID>,
    val key: SerializableProperty<T, *>? = null,
) : ApiWebsocket<USER, TypedServerPath0, Condition<T>, CollectionUpdates<T, ID>, ModelRestUpdatesWebsocketData<T, ID>>(
    path,
    ModelRestUpdatesWebsocketData.serializer(info.serialization.serializer, info.serialization.idSerializer)
) {
    constructor(
        path: ServerPath,
        info: ModelInfo<USER, T, ID>,
        key: SerializableProperty<T, *>? = null,
    ):this(TypedServerPath0(path), info, key)
    @Deprecated("database parameter no longer required")
    constructor(
        path: ServerPath,
        database: () -> Database,
        info: ModelInfo<USER, T, ID>,
        key: SerializableProperty<T, *>? = null,
    ):this(path, info, key)

    override val authOptions: AuthOptions<USER> get() = info.authOptions
    override val inputType: KSerializer<Condition<T>> = Condition.serializer(info.serialization.serializer)
    override val outputType: KSerializer<CollectionUpdates<T, ID>> = CollectionUpdates.serializer(info.serialization.serializer, info.serialization.idSerializer)
    override val summary: String = "Updates"
    override val belongsToInterface: Documentable.InterfaceInfo = Documentable.InterfaceInfo(path.path, "ClientModelRestEndpointsPlusUpdatesWebsocket", listOf(
        info.serialization.serializer,
        info.serialization.idSerializer
    ))
    override val description: String = "Streams updates about items that fulfill your condition."

    override suspend fun willConnect(
        auth: AuthAndPathParts<USER, TypedServerPath0>,
        request: WebSocketConnectRequest
    ): ModelRestUpdatesWebsocketData<T, ID> {
        return ModelRestUpdatesWebsocketData(
            user = auth.authOrNull?.serializable(now().plus(1.days)),
            mask = info.collection(auth).mask()
        )
    }

    val generalTopic = WebSocketTopic("$path/general", CollectionChanges.serializer(info.serialization.serializer))
    fun hashTopic(hash: Int) = WebSocketTopic("$path/$hash", CollectionChanges.serializer(info.serialization.serializer))

    override suspend fun messageFromClient(
        connection: ApiWebsocketConnection<USER, TypedServerPath0, Condition<T>, CollectionUpdates<T, ID>, ModelRestUpdatesWebsocketData<T, ID>>,
        input: Condition<T>
    ) = with(connection) {
        val p = info.collection(AuthAccessor(currentState.auth(), null))
        val c = p.fullCondition(input).simplify()
        val oldTopics = currentState.topics
        val newTopics = key?.let { key -> c.relevantHashCodesForKey(key) }?.map {
            hashTopic(it)
        } ?: listOf(generalTopic)
        queueStateUpdate { data ->
            data.copy(condition = c, topics = newTopics.mapTo(HashSet()) { it.topic })
        }
        (oldTopics - newTopics.mapTo(HashSet()) { it.topic }).forEach { unsubscribe(it) }
        newTopics.filter { it.topic !in oldTopics }.forEach { subscribe(it) }
        send(CollectionUpdates(condition = input))
    }

    override suspend fun messageFromSubscription(
        connection: ApiWebsocketConnection<USER, TypedServerPath0, Condition<T>, CollectionUpdates<T, ID>, ModelRestUpdatesWebsocketData<T, ID>>,
        topic: String,
        retrieve: TypeRetriever
    ) = with(connection) {
        val toSend = retrieve(generalTopic.type).changes.map { entry ->
            ListChange(
                old = entry.old?.takeIf { currentState.condition(it) }?.let { currentState.mask(it) },
                new = entry.new?.takeIf { currentState.condition(it) }?.let { currentState.mask(it) },
            )
        }.filter { it.old != null || it.new != null }
        val updates = CollectionUpdates(
            updates = toSend.mapNotNull { it.new }.toSet(),
            remove = toSend.mapNotNull { it.old.takeIf { _ -> it.new == null }?._id }.toSet()
        )
        val size = Serialization.json.encodeToString(
            CollectionUpdates.serializer(
                info.serialization.serializer,
                info.serialization.idSerializer
            ), updates
        ).length
        if (size >= 24000) {
            send(CollectionUpdates(overload = true))
        } else {
            send(updates)
        }
    }

    init {
        info.registerChangeListener { changes ->
            generalTopic.publish(changes)
            key?.let { key ->
                val hashes = changes.changes.asSequence().flatMap {
                    listOfNotNull(
                        it.old?.let { key.get(it).hashCode() },
                        it.new?.let { key.get(it).hashCode() },
                    )
                }
                for (hash in hashes) {
                    hashTopic(hash).publish(CollectionChanges(changes.changes.mapNotNull {
                        val old = it.old?.takeIf { key.get(it).hashCode() == hash }
                        val new = it.new?.takeIf { key.get(it).hashCode() == hash }
                        if(old == null && new == null) null
                        else if(old != null && new != null) it  // saves a common allocation
                        else EntryChange(old, new)
                    }))
                }
            }
        }
    }
}


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