package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.uuid.Uuid

class StringTests {
    @Serializable
    data class User(
        override val _id: Uuid,
    ) : HasId<Uuid> {
        companion object : PrincipalType<User, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<User> = serializer()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): User = User(id)
        }
    }

    @Test
    fun strings() {
        println("noAuth = $noAuth")
        println("anyAuth = $anyAuth")
        println("recentRootAuth = $recentRootAuth")
        println("Authenticated = ${AuthRequirement.Authenticated()}")
        println(AuthRequirement.Authenticated { it.fromMasquerade == null })

        println("User auth = ${User.require()}")
        println("User auth = ${User.require(scope = RequiredScope("hello:world"))}")

        println(User.require(scope = RequiredScope("hello:world")) { it.id != Uuid.NIL } or recentRootAuth)

        val auth = User.require() or noAuth
    }
}