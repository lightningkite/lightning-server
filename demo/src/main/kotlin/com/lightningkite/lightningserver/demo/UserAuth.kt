package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.demo.endpoints.UserEndpoints
import com.lightningkite.lightningserver.demo.models.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.eq
import com.lightningkite.services.database.findOne
import com.lightningkite.services.database.get
import com.lightningkite.services.database.insertOne
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.Uuid


object UserAuth : PrincipalType<User, Uuid> {
    override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
    override val subjectSerializer: KSerializer<User> = User.Companion.serializer()
    override val name: String get() = User.serializer().descriptor.serialName.substringAfterLast('.')

    context(server: ServerRuntime)
    override suspend fun fetch(id: Uuid): User =
        UserEndpoints.info.table().get(id) ?: throw NotFoundException()

    context(server: ServerRuntime)
    override suspend fun fetchByProperty(property: String, value: String): User? {
        return when (property) {
            "email" -> UserEndpoints.info.table().findOne(Condition<User> { it.email eq value }) ?: UserEndpoints.info.table()
                .insertOne(User(email = value))

            else -> super.fetchByProperty(property, value)
        }
    }
}