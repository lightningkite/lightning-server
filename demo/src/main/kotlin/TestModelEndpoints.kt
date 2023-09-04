package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningdb.FieldCollection
import com.lightningkite.lightningdb.collection
import com.lightningkite.lightningdb.get
import com.lightningkite.lightningdb.metrics
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.*
import java.util.*
import kotlin.random.Random

class TestModelEndpoints(path: ServerPath): ServerPathGroup(path), ModelInfoWithDefault<TestModel, UUID> {
    override val serialization: ModelSerializationInfo<TestModel, UUID> = ModelSerializationInfo()
    override fun collection(): FieldCollection<TestModel> = Server.database().collection<TestModel>()
    override val authOptions: AuthOptions = setOf(null)
    override suspend fun collection(principal: RequestAuth<*>?): FieldCollection<TestModel> = collection()
    override suspend fun defaultItem(principal: RequestAuth<*>?): TestModel = TestModel()
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
}
