package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.builder.bind
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.ModelRestUpdatesWebsocket
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.database.collection
import java.util.*
import kotlin.random.Random

class TestModelEndpoints: ServerBuilder() {
    val info = Server.database.modelInfo(
        auth = noAuth,
        permissions = { ModelPermissions.allowAll<TestModel>() },
    )

    val rest = path.path("rest") bind ModelRestEndpoints(info)
    val sockets = path.path("rest") bind ModelRestUpdatesWebsocket(info)
}
