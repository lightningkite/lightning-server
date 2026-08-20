package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.send
import com.lightningkite.lightningserver.typed.sdk.*
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.defaultInfo
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.lightningserver.serialization.approximateJsonSize
import com.lightningkite.services.database.*
import kotlinx.serialization.KSerializer
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant


// Condition<T>, CollectionUpdates<T, ID>
/**
 * @property user The authentication that established the connection.
 * @property clientCondition What the client asked to watch, before permissions narrow it. Retained so
 *   revalidation can recombine it with freshly-resolved permissions.
 * @property condition [clientCondition] narrowed by the subject's read permissions, as of
 *   [permissionsCheckedAt].
 * @property mask The read mask in force as of [permissionsCheckedAt].
 * @property permissionsCheckedAt When [condition] and [mask] were last derived. A socket outlives the
 *   moment it connected, so these are a cache with a deadline, not a fact settled at connect.
 */
@Serializable
public data class ModelRestUpdatesWebsocketData<T : HasId<ID>, ID : Comparable<ID>>(
    val user: Authentication<Nothing>?, //USER
    val clientCondition: Condition<T> = Condition.Never,
    val condition: Condition<T> = Condition.Never,
    val mask: Mask<T>,
    val permissionsCheckedAt: Instant = Instant.fromEpochMilliseconds(0),
    val topics: Set<String> = setOf(),
) {
    @Suppress("UNCHECKED_CAST")
    internal fun <USER : HasId<*>?> auth() = user as Authentication<USER & Any>?
}

/**
 * Payload size past which a client is told to resynchronise over HTTP instead of being sent the change.
 */
private const val OVERLOAD_THRESHOLD_BYTES: Int = 24000

/**
 * @param permissionRevalidation How long a connection may keep using permissions it resolved earlier.
 *   Permissions are re-derived on the first push after this elapses, so a revocation takes effect
 *   within this window rather than at reconnect. Shorten it where disclosure matters more than the
 *   cost of re-resolving; it is a ceiling on staleness, not a polling interval, so an idle connection
 *   costs nothing.
 */
public class ModelRestUpdatesWebsocket<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    public val info: ModelInfo<USER, T, ID>,
    public val key: SerializableProperty<T, *>? = null,
    public val permissionRevalidation: Duration = 5.minutes,
) : ServerBuilder() {
    init {
        sdkSettings.clientInterface = ClientModelRestUpdatesWebsocket::class.info(info.serializer, info.idSerializer)
        sdkSettings.defaultInfo = SdkModule.Info(
            interfaceName = info.tableName.pascalCase() + "RestUpdatesWebsocket",
            valueName = "websocket"
        )
    }

    private inner class Websocket :
        ApiWebsocketHandler<PathSpec0, ModelRestUpdatesWebsocketData<T, ID>, USER, Condition<T>, CollectionUpdates<T, ID>> {
        override val auth: AuthRequirement<USER> = info.auth
        override val inputType: KSerializer<Condition<T>> = Condition.serializer(info.serializer)
        override val outputType: KSerializer<CollectionUpdates<T, ID>> =
            CollectionUpdates.serializer(info.serializer, info.idSerializer)
        override val summary: String = "Updates"
        override val description: String = "Streams updates about items that fulfill your condition."
        override val errorCases: List<LSError> get() = listOf()
        override val innerStorageSerializer: KSerializer<ModelRestUpdatesWebsocketData<T, ID>> =
            ModelRestUpdatesWebsocketData.serializer(info.serializer, info.idSerializer)

        context(serverRuntime: ServerRuntime)
        override suspend fun willConnectTyped(access: WebSocketConnectRequestAccess<PathSpec0, USER>): ModelRestUpdatesWebsocketData<T, ID> {
            @Suppress("UNCHECKED_CAST")
            return ModelRestUpdatesWebsocketData(
                user = access.authOrNull as? Authentication<Nothing>,
                mask = info.table(access).mask(),
                permissionsCheckedAt = now(),
            )
        }

        context(connection: ApiWebsocketHandler.Connection<PathSpec0, ModelRestUpdatesWebsocketData<T, ID>, USER, Condition<T>, CollectionUpdates<T, ID>>)
        override suspend fun didConnectTyped() {
        }

        context(connection: ApiWebsocketHandler.Connection<PathSpec0, ModelRestUpdatesWebsocketData<T, ID>, USER, Condition<T>, CollectionUpdates<T, ID>>)
        override suspend fun messageFromClientTyped(frame: Condition<T>) {
            val p = info.table(connection.auth())
            val c = p.fullCondition(frame).simplify()
            val oldTopics: Set<WebSocketSubscriptionRequest<out PathSpec, *>> =
                connection.currentState.topics.mapTo(HashSet()) {
                    connection.server.webSocketTopics.match(connection.internalSerialization.stringArrayFormat, it)
                        ?.let { match -> WebSocketSubscriptionRequest(match.value, match.path.rawPathArguments) }
                        ?: throw IllegalArgumentException(
                            "WebSocket topic $it does not exist.  " +
                                    "Topics must be registered with the server before they can be used."
                        )
                }
            val newTopics: Set<WebSocketSubscriptionRequest<out PathSpec, *>> =
                key?.let { key -> c.relevantHashCodesForKey(key) }?.mapTo(HashSet()) {
                    hashTopic.request(it)
                } ?: setOf(generalTopic.request())
            // This path already re-resolved auth via info.table above, so the mask is refreshed here
            // too — otherwise the condition and the mask would be derived from different moments.
            val refreshedMask = p.mask()
            connection.queueStateUpdate { data ->
                data.copy(
                    clientCondition = frame,
                    condition = c,
                    mask = refreshedMask,
                    permissionsCheckedAt = now(),
                    topics = newTopics.mapTo(HashSet()) { it.pathInContext.path(connection.internalSerialization.stringArrayFormat) })
            }
            (oldTopics - newTopics.toHashSet()).forEach { connection.unsubscribe(it) }
            newTopics.filter { it !in oldTopics }.forEach { connection.subscribe(it) }
            connection.send(CollectionUpdates(condition = frame))
        }

        context(connection: ApiWebsocketHandler.Connection<PathSpec0, ModelRestUpdatesWebsocketData<T, ID>, USER, Condition<T>, CollectionUpdates<T, ID>>)
        override suspend fun messageFromSubscriptionTyped(topic: WebSocketSubscriptionMessage<*, *>) {
            val state = connection.currentState
            val now = now()

            state.user?.expiration?.let { expiration ->
                if (expiration <= now) {
                    connection.close(WebSocketClose.VIOLATED_POLICY)
                    return
                }
            }

            if (now - state.permissionsCheckedAt > permissionRevalidation) {
                val table = try {
                    info.table(connection.auth())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Auth no longer resolves — the session was terminated or the credential revoked.
                    connection.close(WebSocketClose.VIOLATED_POLICY)
                    return
                }
                // Resolved outside updateStateImmediately: its modification lambda is not suspending.
                val refreshedCondition = table.fullCondition(state.clientCondition).simplify()
                val refreshedMask = table.mask()
                connection.updateStateImmediately {
                    it.copy(
                        condition = refreshedCondition,
                        mask = refreshedMask,
                        permissionsCheckedAt = now,
                    )
                }
            }

            @Suppress("Unchecked_cast")
            val message = when (topic.topic) {
                generalTopic -> topic.value
                hashTopic -> topic.value
                else -> return
            } as CollectionChanges<T>
            val unmasked = message.changes.mapNotNull { entry ->
                val old = entry.old?.takeIf { connection.currentState.condition(it) }
                val new = entry.new?.takeIf { connection.currentState.condition(it) }
                if(old != null || new != null) ListChange(old = old, new = new)
                else null
            }
            if (unmasked.isEmpty()) return
            val unmaskedUpdates = CollectionUpdates(
                updates = unmasked.mapNotNull { it.new }.toSet(),
                remove = unmasked.mapNotNull { it.old.takeIf { _ -> it.new == null }?._id }.toSet()
            )
            // Measured on the unmasked payload: masking only ever removes content, so this errs toward
            // declaring an overload, which costs the client an HTTP refetch rather than a huge frame.
            val approxSize = connection.externalSerialization.approximateJsonSize(
                CollectionUpdates.serializer(info.serializer, info.idSerializer),
                unmaskedUpdates,
                limit = OVERLOAD_THRESHOLD_BYTES,
            )
            if (approxSize >= OVERLOAD_THRESHOLD_BYTES) {
                connection.send(CollectionUpdates(overload = true))
            } else {
                val safeUpdates = unmaskedUpdates.copy(
                    updates = unmaskedUpdates.updates.mapTo(HashSet()) { connection.currentState.mask(it) },
                )
                connection.send(safeUpdates)
            }
        }

        context(connection: ApiWebsocketHandler.Connection<PathSpec0, ModelRestUpdatesWebsocketData<T, ID>, USER, Condition<T>, CollectionUpdates<T, ID>>)
        override suspend fun disconnectTyped(reason: WebSocketClose) {
        }
    }

    public val websocket: ApiWebsocketHandler<PathSpec0, ModelRestUpdatesWebsocketData<T, ID>, USER, Condition<T>, CollectionUpdates<T, ID>> =
        path bind Websocket()

    public val generalTopic: WebSocketTopic<PathSpec0, CollectionChanges<T>> =
        path.path("general").topic(CollectionChanges.serializer(info.serializer))
    public val hashTopic: WebSocketTopic<PathSpec1<Int>, CollectionChanges<T>> =
        path.arg<Int>("id").topic(CollectionChanges.serializer(info.serializer))

    init {
        info.registerChangeListener { changes ->
            generalTopic.send(changes)
            key?.let { key ->
                val hashes = changes.changes.asSequence().flatMap {
                    listOfNotNull(
                        it.old?.let { key.get(it).hashCode() },
                        it.new?.let { key.get(it).hashCode() },
                    )
                }.distinct()
                for (hash in hashes) {
                    hashTopic.send(hash, CollectionChanges(changes.changes.mapNotNull {
                        val old = it.old?.takeIf { key.get(it).hashCode() == hash }
                        val new = it.new?.takeIf { key.get(it).hashCode() == hash }
                        if (old == null && new == null) null
                        else if (old != null && new != null) it  // saves a common allocation
                        else EntryChange(old, new)
                    }))
                }
            }
        }
    }
}


private fun <T, V> Condition<T>.relevantHashCodesForKey(key: SerializableProperty<T, V>): Set<Int>? = when (this) {
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