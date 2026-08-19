package com.lightningkite.lightningserver.testdata

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class User(
    override val _id: Uuid,
    val name: String,
) : HasId<Uuid>

@Serializable
data class Post(
    override val _id: Uuid,
    val user: Uuid,
    val name: String,
    val body: String,
    val likes: Int
) : HasId<Uuid>

object Server : ServerBuilder() {

}

object PostEndpoints : ServerBuilder() {

}

object UserEndpoints : ServerBuilder() {
    context(runtime: ServerRuntime, test: TestDataGeneration<User>)
    suspend fun testUser()
}