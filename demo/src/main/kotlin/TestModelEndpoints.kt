package com.lightningkite.lightningserverdemo

import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.*
import com.lightningkite.lightningserver.typed.AuthAccessor
import java.util.*
import kotlin.random.Random

class TestModelEndpoints(path: ServerPath): ServerPathGroup(path) {
    val info = modelInfoWithDefault(
        serialization = ModelSerializationInfo(),
        authOptions = noAuth,
        getBaseCollection = {Server.database().collection<TestModel>()},
        defaultItem = { TestModel() },
    )

    val rest = ModelRestEndpoints(path("rest"), info)
    val restWebsocket = path("rest").restApiWebsocket(Server.database, info)
    val dump = ModelDumpEndpoints(path("rest"), info, Authentication.isSuperUser, Server.files, Server.email)
}
