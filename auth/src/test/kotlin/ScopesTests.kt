import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.RequestPredicates
import com.lightningkite.lightningserver.auth.Scope
import com.lightningkite.lightningserver.auth.acceptsScope
import com.lightningkite.lightningserver.auth.acceptsAllScopes
import com.lightningkite.lightningserver.auth.acceptsScopes
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
        assert("auth".acceptsScope("auth"))
        assert("auth".acceptsScope("auth:sub"))
        assert("auth:sub".acceptsScope("auth:sub"))
        assert(!"auth:sub".acceptsScope("auth"))
        assert("auth".acceptsScope("auth:sub1:sub2:sub3"))
    }

    @Test
    fun multiScopeAcceptance() {
        assert(
            setOf("auth", "scope").acceptsAllScopes(setOf("auth", "scope"))
        )
        assert(
            setOf("auth", "scope").acceptsAllScopes(setOf("auth:sub", "scope:sub"))
        )
        assert(
            setOf("auth:sub", "scope").acceptsAllScopes(setOf("auth:sub", "scope:sub"))
        )
        assert(
            setOf("*").acceptsAllScopes(setOf("auth", "scope", "*"))
        )
        assert(
            !setOf("auth:sub", "scope").acceptsAllScopes(setOf("*"))
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
        limitTo: RequestPredicates? = null,
        forbid: RequestPredicates? = null,
        test: context(ServerRuntime) (Authentication<*>) -> Unit
    ) {
        TestRunner(Server).run {
            test(Authentication(User, Uuid.random(), limitTo = limitTo, forbid = forbid))
        }
    }

    @Test
    fun limitToScopes() {
        fun testLimits(limits: Set<Scope>, actual: Set<Scope>, expected: Boolean) {
            testAuth(
                limitTo = RequestPredicates(scopes = limits)
            ) {
                assertEquals(expected, it.acceptsScopes(actual), "limits: ${it.limitTo?.scopes}  actual: $actual")
            }
        }

        testLimits(setOf("auth"), setOf("auth"), true)
        testLimits(setOf("auth"), setOf("auth:sub"), true)
        testLimits(setOf("auth"), setOf("auth:sub:sub"), true)
        testLimits(setOf("auth:sub"), setOf("auth:sub:sub"), true)
        testLimits(setOf("auth:sub", "other"), setOf("auth:sub:sub"), true)
        testLimits(setOf("auth:sub"), setOf("other"), false)
        testLimits(setOf("other:sub"), setOf("other"), false)
        testLimits(setOf("*"), setOf("other"), true)
        testLimits(setOf("other"), setOf("*", "other"), false)
    }

    @Test
    fun forbidScopes() {
        fun testForbidden(forbid: Set<Scope>, actual: Set<Scope>, expected: Boolean) {
            testAuth(
                forbid = RequestPredicates(scopes = forbid)
            ) {
                assertEquals(expected, it.acceptsScopes(actual), "forbid: ${it.limitTo?.scopes}  actual: $actual")
            }
        }

        testForbidden(setOf("auth"), setOf("some", "other", "scopes", "auth:sub"), false)

        testForbidden(setOf("auth"), setOf("auth"), false)
        testForbidden(setOf("auth"), setOf("auth:sub"), false)
        testForbidden(setOf("auth"), setOf("auth:sub:sub"), false)
        testForbidden(setOf("auth:sub"), setOf("auth:sub:sub"), false)
        testForbidden(setOf("auth:sub", "other"), setOf("auth:sub:sub"), false)
        testForbidden(setOf("auth:sub"), setOf("other"), true)
        testForbidden(setOf("other:sub"), setOf("other"), true)
        testForbidden(setOf("*"), setOf("other"), false)
        testForbidden(setOf("other"), setOf("*", "other"), false)
    }
}