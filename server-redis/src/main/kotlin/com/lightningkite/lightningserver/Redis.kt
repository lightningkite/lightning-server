package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.pubsub.RedisPubSub
import com.lightningkite.lightningserver.cache.RedisCache

object Redis {
    init {
        RedisCache
        RedisPubSub
    }
}