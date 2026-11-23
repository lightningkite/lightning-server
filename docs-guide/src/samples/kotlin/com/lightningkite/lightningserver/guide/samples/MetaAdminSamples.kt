package com.lightningkite.lightningserver.guide.samples

// region meta-imports
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.typed.MetaEndpoints
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Database
// endregion meta-imports

// region meta-server
object MetaServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())

    val meta = path.path("meta") module MetaEndpoints(
        packageName = "com.example.myapp",
        cache = cache,
    )

    init {
        registerBasicMediaTypeCoders()
    }
}
// endregion meta-server
