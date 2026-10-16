package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.pubsub.PubSub
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.serializer

private class CacheMutex(
    val key: String,
    val pubSub: PubSub,
    val cache: Cache,
) {
    companion object {
        // Constructors can't take context parameters, so this factory takes the ServerRuntime's
        // place: everywhere else a ServerRuntime is context, never a stored or passed parameter.
        context(server: ServerRuntime)
        operator fun invoke(key: String, pubSub: Runtime<PubSub>, cache: Runtime<Cache>): CacheMutex =
            CacheMutex(key, context(server) { pubSub() }, context(server) { cache() })
    }

    private fun channel() = pubSub.get(key, Unit.serializer())

    suspend fun lock(): Boolean = cache.setIfNotExists(key, Unit, Unit.serializer())

    suspend fun getLock() {
        while (!lock()) {
            channel().first()   // await signal of lock release
        }
    }

    suspend fun unlock() {
        cache.remove(key)
        channel().emit(Unit)
    }
}