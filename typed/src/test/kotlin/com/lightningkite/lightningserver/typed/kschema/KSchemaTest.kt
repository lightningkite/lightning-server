package com.lightningkite.lightningserver.typed.kschema

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.MetaEndpoints
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Database
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Instant

class KSchemaTest {
    object TestServer : ServerBuilder() {
        init {
            registerBasicMediaTypeCoders()
        }

        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())

        // TODO: Note that using 'bind' for all of these types makes the overload stupid
        val sample = path.path("sample").post bind ApiHttpHandler(
            summary = "Sample",
            auth = noAuth,
            implementation = { it: Instant ->
                Clock.System.now()
            }
        )
        val meta = path.path("meta") include MetaEndpoints(
            packageName = "com.lightningkite.lightningserver.typed.kschema",
            database = database,
            cache = cache
        )
    }

    @Test
    fun test() {
        TestServer.test(settings = {}) {
            println(Json.encodeToString(lightningServerKSchema))
        }
    }
}