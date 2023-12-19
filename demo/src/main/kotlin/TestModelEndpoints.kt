package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningdb.*
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

class TestModelEndpoints(path: ServerPath): ServerPathGroup(path), ModelInfoWithDefault<HasId<*>?, TestModel, UUID> {
    override val serialization: ModelSerializationInfo<TestModel, UUID> = ModelSerializationInfo()
    override fun collection(): FieldCollection<TestModel> = Server.database().collection<TestModel>()
    override val authOptions: AuthOptions<HasId<*>?> = noAuth
    override suspend fun collection(auth: AuthAccessor<HasId<*>?>): FieldCollection<TestModel> = collection()
    override suspend fun defaultItem(principal: RequestAuth<HasId<*>>?): TestModel = TestModel()
    override fun exampleItem(): TestModel? {
        return TestModel(
            name = listOf("First", "Second", "Third").random(),
            number = Random.nextInt(),
            content = listOf("First", "Second", "Third").random(),
            status = Status.values().random()
        )
    }

    val rest = ModelRestEndpoints(path("rest"), this)
    val restWebsocket = path("rest").restApiWebsocket(Server.database, this)
    val dump = ModelDumpEndpoints(path("rest"), this, Authentication.isSuperUser, Server.files, Server.email)
}
