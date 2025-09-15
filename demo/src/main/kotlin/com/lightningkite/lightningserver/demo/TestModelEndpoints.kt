package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.ModelRestEndpointsAndUpdatesWebsocket.Companion.plus
import com.lightningkite.lightningserver.typed.ModelRestUpdatesWebsocket
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.services.database.ModelPermissions

class TestModelEndpoints: ServerBuilder() {
    val info = Server.database.modelInfo(
        auth = noAuth,
        permissions = { ModelPermissions.allowAll<TestModel>() },
    )

    val rest = path.path("rest") include ModelRestEndpoints(info) + ModelRestUpdatesWebsocket(info)
}
