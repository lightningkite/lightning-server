@file:UseContextualSerialization(Instant::class)
@file:OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)

package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.auth.Authorization
import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.tasks.startup
import com.lightningkite.lightningserver.tasks.task
import com.lightningkite.lightningserver.typed.ApiWebsocket
import com.lightningkite.lightningserver.typed.typedWebsocket
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.*
import java.time.Duration
import java.time.Instant
import kotlin.reflect.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.internal.GeneratedSerializer
import java.time.*
import java.util.*

fun prepare__WebSocketDatabaseChangeSubscriptionFields() {
    __WebSocketDatabaseChangeSubscription::_id.setCopyImplementation { original, value -> original.copy(_id = value) }
    __WebSocketDatabaseChangeSubscription::databaseId.setCopyImplementation { original, value -> original.copy(databaseId = value) }
    __WebSocketDatabaseChangeSubscription::user.setCopyImplementation { original, value -> original.copy(user = value) }
    __WebSocketDatabaseChangeSubscription::condition.setCopyImplementation { original, value -> original.copy(condition = value) }
    __WebSocketDatabaseChangeSubscription::mask.setCopyImplementation { original, value -> original.copy(mask = value) }
    __WebSocketDatabaseChangeSubscription::establishedAt.setCopyImplementation { original, value -> original.copy(establishedAt = value) }
}
val <K> PropChain<K, __WebSocketDatabaseChangeSubscription>._id: PropChain<K, String> get() = this[__WebSocketDatabaseChangeSubscription::_id]
val <K> PropChain<K, __WebSocketDatabaseChangeSubscription>.databaseId: PropChain<K, String> get() = this[__WebSocketDatabaseChangeSubscription::databaseId]
val <K> PropChain<K, __WebSocketDatabaseChangeSubscription>.user: PropChain<K, String?> get() = this[__WebSocketDatabaseChangeSubscription::user]
val <K> PropChain<K, __WebSocketDatabaseChangeSubscription>.condition: PropChain<K, String> get() = this[__WebSocketDatabaseChangeSubscription::condition]
val <K> PropChain<K, __WebSocketDatabaseChangeSubscription>.mask: PropChain<K, String> get() = this[__WebSocketDatabaseChangeSubscription::mask]
val <K> PropChain<K, __WebSocketDatabaseChangeSubscription>.establishedAt: PropChain<K, Instant> get() = this[__WebSocketDatabaseChangeSubscription::establishedAt]
