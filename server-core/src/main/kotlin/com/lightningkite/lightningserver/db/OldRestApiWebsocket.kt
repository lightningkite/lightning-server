@file:UseContextualSerialization(Instant::class)

package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.typed.ApiWebsocket
import com.lightningkite.lightningserver.typed.AuthAccessor
import com.lightningkite.lightningserver.typed.AuthAndPathParts
import com.lightningkite.lightningserver.typed.TypedServerPath0
import com.lightningkite.lightningserver.websocket.TypeRetriever
import com.lightningkite.lightningserver.websocket.WebSocketConnectRequest
import com.lightningkite.lightningserver.websocket.WebSocketTopic
import com.lightningkite.now
import com.lightningkite.serialization.SerializableProperty
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlin.time.Duration.Companion.days

@Serializable
data class OldRestApiWebsocketData<T : HasId<ID>, ID : Comparable<ID>>(
    val user: RequestAuthSerializable?, //USER
    val condition: Query<T> = Query(Condition.Never),
    val mask: Mask<T>,
    val topics: Set<String> = setOf(),
) {
    @Suppress("UNCHECKED_CAST")
    fun <USER : HasId<*>?> auth() = user?.real() as? RequestAuth<USER & Any>
}

@LightningServerDsl
fun <USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> ServerPath.restApiWebsocket(
    info: ModelInfo<USER, T, ID>,
    key: SerializableProperty<T, *>? = null,
): ApiWebsocket<USER, TypedServerPath0, Query<T>, ListChange<T>, OldRestApiWebsocketData<T, ID>> =
    OldRestApiWebsocket(TypedServerPath0(this), info, key)

class OldRestApiWebsocket<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    override val path: TypedServerPath0,
    val info: ModelInfo<USER, T, ID>,
    val key: SerializableProperty<T, *>? = null,
) : ApiWebsocket<USER, TypedServerPath0, Query<T>, ListChange<T>, OldRestApiWebsocketData<T, ID>>() {
    override val authOptions: AuthOptions<USER> get() = info.authOptions
    override val inputType: KSerializer<Query<T>> = Query.serializer(info.serialization.serializer)
    override val outputType: KSerializer<ListChange<T>> = ListChange.serializer(info.serialization.serializer)
    override val storageSerializer: KSerializer<OldRestApiWebsocketData<T, ID>> =
        OldRestApiWebsocketData.serializer(info.serialization.serializer, info.serialization.idSerializer)
    override val summary: String = "Watch"

    override suspend fun AuthAndPathParts<USER, TypedServerPath0>.willConnect(
        request: WebSocketConnectRequest
    ): OldRestApiWebsocketData<T, ID> {
        return OldRestApiWebsocketData(
            user = authOrNull?.serializable(now().plus(1.days)),
            mask = info.collection(this).mask()
        )
    }
    
    val generalTopic = WebSocketTopic("$path/general", CollectionChanges.serializer(info.serialization.serializer))
    fun hashTopic(hash: Int) = WebSocketTopic("$path/$hash", CollectionChanges.serializer(info.serialization.serializer))

    override suspend fun messageFromClient(
        connection: Mid<USER, TypedServerPath0, Query<T>, ListChange<T>, OldRestApiWebsocketData<T, ID>>,
        input: Query<T>
    ) = with(connection) {
        val p = info.collection(AuthAccessor(currentState.auth(), null))
        val q = input.copy(condition = p.fullCondition(input.condition).simplify())
        val oldTopics = currentState.topics
        val newTopics = key?.let { key -> q.condition.relevantHashCodesForKey(key) }?.map {
            hashTopic(it)
        } ?: listOf(generalTopic)
        queueStateUpdate { data ->
            data.copy(condition = q, topics = newTopics.mapTo(HashSet()) { it.topic })
        }
        (oldTopics - newTopics.mapTo(HashSet()) { it.topic }).forEach { unsubscribe(it) }
        newTopics.filter { it.topic !in oldTopics }.forEach { subscribe(it) }
        send(ListChange(wholeList = p.query(q).toList()))
    }

    override suspend fun messageFromSubscription(
        connection: Mid<USER, TypedServerPath0, Query<T>, ListChange<T>, OldRestApiWebsocketData<T, ID>>,
        topic: String,
        retrieve: TypeRetriever
    ) = with(connection) {
        val toSend = retrieve(generalTopic.type).changes.map { entry ->
            ListChange(
                old = entry.old?.takeIf { currentState.condition.condition(it) }?.let { currentState.mask(it) },
                new = entry.new?.takeIf { currentState.condition.condition(it) }?.let { currentState.mask(it) },
            )
        }.filter { it.old != null || it.new != null }
        if (toSend.size > 10) {
            send(ListChange(wholeList = info.collection().query(currentState.condition).toList()))
        } else {
            toSend.forEach { c ->
                send(c)
            }
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
