package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.demo.endpoints.AuthExamplesEndpoints
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.database.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AuthExamplesEndpointsTest {

    @Test
    fun whoAmIReturnsTheCallersOwnIdentity() = runBlocking {
        TestHelper.testServer {
            val user = Server.userInfo.table().insertOne(User(email = "whoami@example.com"))!!
            val auth = Authentication(Server.UserAuth, id = user._id, sessionId = null)

            val result = AuthExamplesEndpoints.whoAmI.test(auth, Unit)

            assertEquals(user._id, result.id)
            assertEquals(user.email, result.email)
            assertEquals(false, result.isSuperUser)
        }
    }

    // whoAmI requires auth: AuthRequirement<User> (non-optional), so .test() only accepts a real
    // Authentication<User> - there is no way to even construct an unauthenticated call to it
    // through the typed test helper. That's the point: the requirement is enforced by the type
    // system, not just checked at runtime.

    @Test
    fun greetIsGenericForAnonymousCallers() = runBlocking {
        TestHelper.testServer {
            val result = AuthExamplesEndpoints.greet.test(null, Unit)
            assertEquals("Hello, anonymous visitor!", result)
        }
    }

    @Test
    fun greetIsPersonalForLoggedInCallers() = runBlocking {
        TestHelper.testServer {
            val user = Server.userInfo.table().insertOne(User(email = "greet@example.com"))!!
            val auth = Authentication(Server.UserAuth, id = user._id, sessionId = null)

            val result = AuthExamplesEndpoints.greet.test(auth, Unit)

            assertEquals("Welcome back, greet@example.com!", result)
        }
    }

    @Test
    fun adminOnlyRejectsNonAdminsWith403() = runBlocking {
        TestHelper.testServer {
            val user = Server.userInfo.table().insertOne(User(email = "regular@example.com"))!!
            val auth = Authentication(Server.UserAuth, id = user._id, sessionId = null)

            val exception = assertFailsWith<com.lightningkite.lightningserver.HttpStatusException> {
                AuthExamplesEndpoints.adminOnly.test(auth, Unit)
            }
            assertEquals(HttpStatus.Forbidden, exception.status)
        }
    }

    @Test
    fun adminOnlyAllowsSuperUsers() = runBlocking {
        TestHelper.testServer {
            val admin = Server.userInfo.table().insertOne(User(email = "admin@example.com", isSuperUser = true))!!
            val auth = Authentication(Server.UserAuth, id = admin._id, sessionId = null)

            val result = AuthExamplesEndpoints.adminOnly.test(auth, Unit)

            assertTrue(result.contains("admin@example.com"))
        }
    }

    /**
     * Proves that ModelPermissions.readMask on Server.userInfo actually masks fields for a
     * non-self, non-admin caller - the concrete manifestation the task asked for, rather than a
     * new endpoint duplicating what GET /user/rest/{id} already does.
     */
    @Test
    fun aStrangerSeesAnotherUsersHashedPasswordMasked() = runBlocking {
        TestHelper.testServer {
            val target = Server.userInfo.table().insertOne(
                User(email = "target@example.com", hashedPassword = "super-secret-hash")
            )!!
            val stranger = Server.userInfo.table().insertOne(User(email = "stranger@example.com"))!!
            val strangerAuth = Authentication(Server.UserAuth, id = stranger._id, sessionId = null)

            val seenByStranger = Server.UserEndpoints.rest.detail.test(target._id, strangerAuth, Unit)
            assertEquals("", seenByStranger.hashedPassword)

            // Sanity check: the target sees their own hash unmasked.
            val targetAuth = Authentication(Server.UserAuth, id = target._id, sessionId = null)
            val seenBySelf = Server.UserEndpoints.rest.detail.test(target._id, targetAuth, Unit)
            assertEquals("super-secret-hash", seenBySelf.hashedPassword)
        }
    }
}
