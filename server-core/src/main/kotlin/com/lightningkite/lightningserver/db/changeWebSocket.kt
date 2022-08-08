package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.AuthInfo
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.serializerOrContextual
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.tasks.task
import com.lightningkite.lightningserver.typed.ApiWebsocket
import com.lightningkite.lightningserver.typed.typedWebsocket
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.*

@LightningServerDsl
inline fun <reified USER, reified T : HasId<ID>, reified ID : Comparable<ID>> ServerPath.restApiWebsocket(
    noinline database: () -> Database,
    noinline baseCollection: (Database) -> AbstractSignalFieldCollection<T> = { database().collection<T>() as AbstractSignalFieldCollection<T> },
    noinline collection: suspend FieldCollection<T>.(USER) -> FieldCollection<T>
): ApiWebsocket<USER, Query<T>, ListChange<T>> = restApiWebsocket(
    AuthInfo(),
    serializerOrContextual(),
    serializerOrContextual(),
    database,
    baseCollection,
    collection
)

@LightningServerDsl
fun <USER, T : HasId<ID>, ID : Comparable<ID>> ServerPath.restApiWebsocket(
    authInfo: AuthInfo<USER>,
    serializer: KSerializer<T>,
    userSerializer: KSerializer<USER>,
    database: () -> Database,
    baseCollection: (Database) -> AbstractSignalFieldCollection<T>,
    collection: suspend FieldCollection<T>.(USER) -> FieldCollection<T>
): ApiWebsocket<USER, Query<T>, ListChange<T>> {
    prepareModels()
    val modelName = serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.')
    val modelIdentifier = serializer.descriptor.serialName
    fun subscriptionDb() = database().collection<__WebSocketDatabaseChangeSubscription>()
    return typedWebsocket<USER, Query<T>, ListChange<T>>(
        authInfo = authInfo,
        inputType = Query.serializer(serializer),
        outputType = ListChange.serializer(serializer),
        summary = "Watch",
        description = "Gets a changing list of ${modelName}s that match the given query.",
        errorCases = listOf(),
        connect = { event ->
            subscriptionDb().insertOne(
                __WebSocketDatabaseChangeSubscription(
                    _id = event.id,
                    databaseId = modelIdentifier,
                    condition = "{\"Never\":true}",
                    user = Serialization.json.encodeToString(userSerializer, event.user)
                )
            )
        },
        message = { event ->
            val existing = subscriptionDb().get(event.id) ?: return@typedWebsocket
            val user = Serialization.json.decodeFromString(userSerializer, existing.user)
            val p = collection(baseCollection(database()), user)
            val q = event.content.copy(condition = p.fullCondition(event.content.condition))
            val c = Serialization.json.encodeToString(Query.serializer(serializer), q)
            subscriptionDb().updateOne(
                condition = condition { it._id eq event.id },
                modification = modification { it.condition assign c }
            )
            send(event.id, ListChange(wholeList = baseCollection(database()).collection(user).query(q).toList()))
        },
        disconnect = { event ->
            subscriptionDb().deleteMany(condition { it._id eq event.id })
        }
    ).apply {
        val sendWsChanges = task(
            "$modelIdentifier.sendWsChanges",
            CollectionChanges.serializer(serializer)
        ) { changes: CollectionChanges<T> ->
            val asyncs = ArrayList<Deferred<Unit>>()
            subscriptionDb().find(condition { it.databaseId eq modelIdentifier }).collect {
                asyncs += async {
                    val p = collection(
                        baseCollection(database()),
                        Serialization.json.decodeFromString(userSerializer, it.user)
                    )
                    val c = Serialization.json.decodeFromString(Query.serializer(serializer), it.condition)
                    for (entry in changes.changes) {
                        send(it._id, ListChange(
                            old = entry.old?.takeIf { c.condition(it) }?.let { p.mask(it) },
                            new = entry.new?.takeIf { c.condition(it) }?.let { p.mask(it) },
                        )
                        )
                    }
                }
            }
            asyncs.awaitAll()
        }
        Tasks.startup {
            baseCollection(database()).signals.add { changes -> sendWsChanges(changes) }
        }
    }
}

@Serializable
@DatabaseModel
@Suppress("ClassName")
data class __WebSocketDatabaseChangeSubscription(
    override val _id: String,
    @Index val databaseId: String,
    val user: String, //USER
    val condition: String //Condition<T>
) : HasId<String>