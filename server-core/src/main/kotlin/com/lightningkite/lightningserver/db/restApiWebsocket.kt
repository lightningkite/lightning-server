@file:UseContextualSerialization(Instant::class)

package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.Authorization
import com.lightningkite.lightningserver.auth.cast
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.serialization.Serialization

import com.lightningkite.lightningserver.tasks.startup
import com.lightningkite.lightningserver.tasks.task
import com.lightningkite.lightningserver.typed.ApiWebsocket
import com.lightningkite.lightningserver.typed.typedWebsocket
import com.lightningkite.lightningserver.websocket.WebSocketIdentifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.forEach
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.*
import java.time.Instant

@LightningServerDsl
fun <USER, T : HasId<ID>, ID : Comparable<ID>> ServerPath.restApiWebsocket(
    database: () -> Database,
    info: ModelInfo<USER, T, ID>,
): ApiWebsocket<USER, Query<T>, ListChange<T>> {
    prepareModels()
    val modelName = info.serialization.serializer.descriptor.serialName.substringBefore('<').substringAfterLast('.')
    val modelIdentifier = info.serialization.serializer.descriptor.serialName
    fun subscriptionDb() = database().collection<__WebSocketDatabaseChangeSubscription>()
    return typedWebsocket<USER, Query<T>, ListChange<T>>(
        authInfo = info.serialization.authInfo,
        inputType = Query.serializer(info.serialization.serializer),
        outputType = ListChange.serializer(info.serialization.serializer),
        summary = "Watch",
        description = "Gets a changing list of ${modelName}s that match the given query.",
        errorCases = listOf(),
        connect = { event ->
            val user = event.user?.takeUnless { it == Unit }?.let {
                @Suppress("UNCHECKED_CAST")
                (Authorization.handler as Authorization.Handler<USER>).userToIdString(it)
            }
            val collection = info.collection(event.user)
            subscriptionDb().insertOne(
                __WebSocketDatabaseChangeSubscription(
                    _id = event.id,
                    databaseId = modelIdentifier,
                    condition = "{\"Never\":true}",
                    user = user,
                    mask = Serialization.json.encodeToString(
                        Mask.serializer(info.serialization.serializer),
                        collection.mask()
                    ),
                    establishedAt = Instant.now()
                )
            )
        },
        message = { event ->
            val existing = subscriptionDb().get(event.id) ?: throw NotFoundException()
            val user = existing.user?.let {
                @Suppress("UNCHECKED_CAST")
                (Authorization.handler as Authorization.Handler<USER>).idStringToUser(it)
            }
            val p = info.collection(info.serialization.authInfo.cast(user))
            val q = event.content.copy(condition = p.fullCondition(event.content.condition).simplify())
            val c = Serialization.json.encodeToString(Query.serializer(info.serialization.serializer), q)
            subscriptionDb().updateOne(
                condition = condition { it._id eq event.id },
                modification = modification {
                    it.condition assign c
                },
            )
            send(event.id, ListChange(wholeList = p.query(q).toList()))
        },
        disconnect = { event ->
            subscriptionDb().deleteMany(condition { it._id eq event.id })
        }
    ).apply {
        val sendWsChanges = task(
            "$modelIdentifier.sendWsChanges",
            CollectionChanges.serializer(info.serialization.serializer)
        ) { changes: CollectionChanges<T> ->
            val jobs = ArrayList<Job>()
            subscriptionDb().find(condition { it.databaseId eq modelIdentifier }).collect {
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
                    if(toSend.size > 10) {
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

@Serializable
@DatabaseModel
@Suppress("ClassName")
data class __WebSocketDatabaseChangeSubscription(
    override val _id: WebSocketIdentifier,
    @Index val databaseId: String,
    val user: String?, //USER
    val condition: String, //Query<T>
    val mask: String, //Mask<T>
    val establishedAt: Instant,
) : HasId<WebSocketIdentifier>