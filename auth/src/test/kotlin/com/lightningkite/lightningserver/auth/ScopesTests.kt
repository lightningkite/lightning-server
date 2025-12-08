package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.services.database.HasId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class ScopesTests {
    @Test
    fun singleScopeAcceptance() {
        assert(GrantedScope("auth").meetsRequirements(RequiredScope("auth")))
        assert(GrantedScope("auth").meetsRequirements(RequiredScope("auth:sub")))
        assert(GrantedScope("auth:sub").meetsRequirements(RequiredScope("auth:sub")))
        assert(!GrantedScope("auth:sub").meetsRequirements(RequiredScope("auth")))
        assert(GrantedScope("auth").meetsRequirements(RequiredScope("auth:sub1:sub2:sub3")))
    }

    @Test
    fun multiScopeAcceptance() {
        assert(
            setOf("auth", "scope").mapTo(HashSet(), ::GrantedScope).meetsRequirements(setOf("auth", "scope").mapTo(HashSet(), ::RequiredScope))
        )
        assert(
            setOf("auth", "scope").mapTo(HashSet(), ::GrantedScope).meetsRequirements(setOf("auth:sub", "scope:sub").mapTo(HashSet(), ::RequiredScope))
        )
        assert(
            setOf("auth:sub", "scope").mapTo(HashSet(), ::GrantedScope).meetsRequirements(setOf("auth:sub", "scope:sub").mapTo(HashSet(), ::RequiredScope))
        )
        assert(
            setOf("*").mapTo(HashSet(), ::GrantedScope).meetsRequirements(setOf("auth", "scope", "*").mapTo(HashSet(), ::RequiredScope))
        )
        assert(
            !setOf("auth:sub", "scope").mapTo(HashSet(), ::GrantedScope).meetsRequirements(setOf("*").mapTo(HashSet(), ::RequiredScope))
        )
    }

    private object Server : ServerBuilder()

    @Serializable
    data class User(
        override val _id: Uuid
    ) : HasId<Uuid> {
        companion object : PrincipalType<User, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<User> = serializer()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): User = User(id)
        }
    }

    private fun testAuth(
        scopes: Set<GrantedScope> = setOf(GrantedScope.root),
        test: context(ServerRuntime) (Authentication<*>) -> Unit
    ) {
        TestRunner(Server).run {
            test(Authentication(User, Uuid.random(), sessionId = null, scopes = scopes))
        }
    }

    @Test
    fun limitToScopes() {
        fun testLimits(required: Set<String>, granted: Set<String>, permitted: Boolean) {
            testAuth(
                scopes = granted.mapTo(HashSet(), ::GrantedScope)
            ) {
                assertEquals(permitted, it.meetsRequirements(required.mapTo(HashSet(), ::RequiredScope)), "limits: ${it.scopes}  actual: $required")
            }
        }

        testLimits(
            granted = setOf("auth"),
            required = setOf("auth"),
            permitted = true
        )
        testLimits(
            granted = setOf("auth"),
            required = setOf("auth:sub"),
            permitted = true
        )
        testLimits(
            granted = setOf("auth"),
            required = setOf("auth:sub:sub"),
            permitted = true
        )
        testLimits(
            granted = setOf("auth:sub"),
            required = setOf("auth:sub:sub"),
            permitted = true
        )
        testLimits(
            granted = setOf("auth:sub", "other"),
            required = setOf("auth:sub:sub"),
            permitted = true
        )
        testLimits(
            granted = setOf("auth:sub"),
            required = setOf("other"),
            permitted = false
        )
        testLimits(
            granted = setOf("other:sub"),
            required = setOf("other"),
            permitted = false
        )
        testLimits(
            granted = setOf("*"),
            required = setOf("other"),
            permitted = true
        )
        testLimits(
            granted = setOf("other"),
            required = setOf("*", "other"),
            permitted = false
        )
    }
}