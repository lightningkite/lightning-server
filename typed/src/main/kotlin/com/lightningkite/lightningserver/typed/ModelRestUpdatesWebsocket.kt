package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.send
import com.lightningkite.lightningserver.websockets.WebSocketClose
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionRequest
import com.lightningkite.lightningserver.websockets.WebSocketTopic
import com.lightningkite.lightningserver.websockets.request
import com.lightningkite.services.database.CollectionChanges
import com.lightningkite.services.database.CollectionUpdates
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.EntryChange
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.ListChange
import com.lightningkite.services.database.Mask
import com.lightningkite.services.database.SerializableProperty
import com.lightningkite.services.database.simplify
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable


// Condition<T>, CollectionUpdates<T, ID>
@Serializable
public data class ModelRestUpdatesWebsocketData<T : HasId<ID>, ID : Comparable<ID>>(
    val user: Authentication<Nothing>?, //USER
    val condition: Condition<T> = Condition.Never,
    val mask: Mask<T>,
    val topics: Set<String> = setOf(),
) {
    @Suppress("UNCHECKED_CAST")
    internal fun <USER : HasId<*>?> auth() = user as Authentication<USER & Any>?
}

public class ModelRestUpdatesWebsocket<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    public val info: ModelInfo<USER, T, ID>,
    public val key: SerializableProperty<T, *>? = null,
): ServerBuilder() {
    public val websocket: ApiWebsocketHandler<PathSpec0, ModelRestUpdatesWebsocketData<T, ID>, USER, Condition<T>, CollectionUpdates<T, ID>> = object: ApiWebsocketHandler<PathSpec0, ModelRestUpdatesWebsocketData<T, ID>, USER, Condition<T>, CollectionUpdates<T, ID>> {
        override val auth: AuthRequirement<USER> = info.auth
        override val inputType: KSerializer<Condition<T>> = Condition.serializer(info.serializer)
        override val outputType: KSerializer<CollectionUpdates<T, ID>> = CollectionUpdates.serializer(info.serializer, info.idSerializer)
        override val summary: String = "Updates"
        override val description: String = "Streams updates about items that fulfill your condition."
        override val belongsToInterface: Documentable.OldInterfaceInfo = Documentable.OldInterfaceInfo("ClientModelRestEndpoints", listOf(
            info.serializer,
            info.idSerializer
        ))
        override val errorCases: List<LSError> get() = listOf()
        override val innerStorageSerializer: KSerializer<ModelRestUpdatesWebsocketData<T, ID>> = ModelRestUpdatesWebsocketData.serializer(info.serializer, info.idSerializer)

        context(serverRuntime: ServerRuntime)
        override suspend fun willConnectTyped(access: WebSocketConnectRequestAccess<PathSpec0, USER>): ModelRestUpdatesWebsocketData<T, ID> {
            @Suppress("UNCHECKED_CAST")
            return ModelRestUpdatesWebsocketData(
                user = access.authOrNull as Authentication<Nothing>,
                mask = info.collection(access).mask()
            )
        }

        context(connection: ApiWebsocketHandler.Connection<PathSpec0, ModelRestUpdatesWebsocketData<T, ID>, USER, Condition<T>, CollectionUpdates<T, ID>>)
        override suspend fun didConnectTyped() {
        }

        context(connection: ApiWebsocketHandler.Connection<PathSpec0, ModelRestUpdatesWebsocketData<T, ID>, USER, Condition<T>, CollectionUpdates<T, ID>>)
        override suspend fun messageFromClientTyped(frame: Condition<T>) {
            val p = info.collection(Access(connection.request, connection.auth()))
            val c = p.fullCondition(frame).simplify()
            val oldTopics: Set<WebSocketSubscriptionRequest<out PathSpec, *>> = connection.currentState.topics.mapTo(HashSet()) {
                connection.server.webSocketTopics.match(connection.internalSerialization.stringArrayFormat, it)
                    ?.let { WebSocketSubscriptionRequest(it.value!!, it.path.rawPathArguments) }
                    ?: throw IllegalArgumentException(
                        "WebSocket topic $it does not exist.  " +
                                "Topics must be registered with the server before they can be used."
                    )
            }
            val newTopics: Set<WebSocketSubscriptionRequest<out PathSpec, *>> = key?.let { key -> c.relevantHashCodesForKey(key) }?.mapTo(HashSet()) {
                hashTopic.request(it)
            } ?: setOf(generalTopic.request())
            connection.queueStateUpdate { data ->
                data.copy(condition = c, topics = newTopics.mapTo(HashSet()) { it.pathInContext.path(connection.internalSerialization.stringArrayFormat) })
            }
            (oldTopics - newTopics.toHashSet()).forEach { connection.unsubscribe(it) }
            newTopics.filter { it !in oldTopics }.forEach { connection.subscribe(it) }
            connection.send(CollectionUpdates(condition = frame))
        }

        context(connection: ApiWebsocketHandler.Connection<PathSpec0, ModelRestUpdatesWebsocketData<T, ID>, USER, Condition<T>, CollectionUpdates<T, ID>>)
        override suspend fun messageFromSubscriptionTyped(topic: WebSocketSubscriptionMessage<*, *>) {
            val message = when(topic.topic) {
                 generalTopic -> topic.value
                 hashTopic -> topic.value
                 else -> return
            } as CollectionChanges<T>
            val toSend = message.changes.map { entry ->
                ListChange(
                    old = entry.old?.takeIf { connection.currentState.condition(it) }?.let { connection.currentState.mask(it) },
                    new = entry.new?.takeIf { connection.currentState.condition(it) }?.let { connection.currentState.mask(it) },
                )
            }.filter { it.old != null || it.new != null }
            if(toSend.isEmpty()) return
            val updates = CollectionUpdates(
                updates = toSend.mapNotNull { it.new }.toSet(),
                remove = toSend.mapNotNull { it.old.takeIf { _ -> it.new == null }?._id }.toSet()
            )
            val size = connection.externalSerialization.json.encodeToString(
                CollectionUpdates.serializer(
                    info.serializer,
                    info.idSerializer
                ), updates
            ).length
            if (size >= 24000) {
                connection.send(CollectionUpdates(overload = true))
            } else {
                connection.send(updates)
            }
        }

        context(connection: ApiWebsocketHandler.Connection<PathSpec0, ModelRestUpdatesWebsocketData<T, ID>, USER, Condition<T>, CollectionUpdates<T, ID>>)
        override suspend fun disconnectTyped(reason: WebSocketClose) {
        }
    }

    public val generalTopic: WebSocketTopic<PathSpec0, CollectionChanges<T>> = path.path("general").topic(CollectionChanges.serializer(info.serializer))
    public val hashTopic: WebSocketTopic<PathSpec1<Int>, CollectionChanges<T>> = path.arg<Int>("id").topic(CollectionChanges.serializer(info.serializer))

    init {
        info.registerChangeListener { changes ->
            generalTopic.send(changes)
            key?.let { key ->
                val hashes = changes.changes.asSequence().flatMap {
                    listOfNotNull(
                        it.old?.let { key.get(it).hashCode() },
                        it.new?.let { key.get(it).hashCode() },
                    )
                }
                for (hash in hashes) {
                    hashTopic.send(hash, CollectionChanges(changes.changes.mapNotNull {
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


private fun <T, V> Condition<T>.relevantHashCodesForKey(key: SerializableProperty<T, V>): Set<Int>? = when(this) {
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

private fun <T> Condition<T>.relevantHashCodes(): Set<Int>? = when (this) {
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