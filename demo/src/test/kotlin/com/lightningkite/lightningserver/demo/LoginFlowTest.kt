// The email-PIN login flow, end to end, through the real endpoints: start proof, receive PIN,
// prove ownership, log in, exchange for an access-bearing session, then call an auth-gated
// endpoint with it. This is the single highest-value test the demo can have - a change to the
// sessions/sessions-email proof machinery, the cache-backed PinHandler, or the email service
// integration would show up here.
package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.demo.endpoints.AuthExamplesEndpoints
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.lightningserver.sessions.proofs.FinishProof
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.*
import com.lightningkite.services.email.EmailService
import com.lightningkite.services.email.TestEmailService
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** PinHandler's alphabet is A-Z without I and O, six characters long (see PinHandler.kt). */
private val PIN_PATTERN = Regex("[A-HJ-NP-Z]{6}")

private fun loginTest(action: suspend context(TestRunner<Server>) Server.() -> Unit) =
    Server.testBlocking(
        settings = {
            database set Database.Settings("ram")
            cache set Cache.Settings("ram")
            email set EmailService.Settings("test")
        },
        action = action,
    )

class LoginFlowTest {

    @Test
    fun emailPinLoginProducesAUsableSession() = loginTest {
        val emails = Server.email() as TestEmailService
        val address = "newcomer@example.com"

        // 1. Ask for a login code. The server emails one and hands back an opaque key.
        val key = Server.proofEmail.start.test(null, address)

        // 2. The code really went out, in the demo's own template.
        val sent = assertNotNull(emails.lastEmailTo(address), "a login code email should have been sent")
        assertEquals("Log In Code", sent.subject)
        val pin = assertNotNull(
            PIN_PATTERN.find(sent.plainText)?.value,
            "the email body should contain the six-character code:\n${sent.plainText}",
        )

        // 3. Trade key + code for a signed proof, then the proof for a session.
        val proof = Server.proofEmail.prove.test(null, FinishProof(key, pin))
        assertEquals("email", proof.property)
        assertEquals(address, proof.value)

        val login = Server.subjects.login.test(null, listOf(proof))
        val refreshToken = assertNotNull(login.refreshToken, "one email proof should be enough to log in")

        // 4. The refresh token exchanges for a usable access token.
        assertTrue(Server.subjects.tokenSimple.test(null, refreshToken).isNotBlank())

        // 5. UserAuth.fetchByProperty creates the User row on first login - verify it landed.
        val user = assertNotNull(
            Server.userInfo.table().findOne(condition { it.email eq address }),
            "logging in for the first time should have created the user",
        )

        // 6. The session actually authenticates - call an auth-gated endpoint with it.
        val auth = Authentication(Server.UserAuth, id = user._id, sessionId = null)
        val whoAmI = AuthExamplesEndpoints.whoAmI.test(auth, Unit)
        assertEquals(user._id, whoAmI.id)
        assertEquals(address, whoAmI.email)
    }

    @Test
    fun aWrongPinIsRejected() = loginTest {
        val key = Server.proofEmail.start.test(null, "newcomer@example.com")

        // "ABCDEF" is a valid-looking code from the right alphabet, so this exercises the real
        // comparison rather than input validation.
        assertFailsWith<Exception> {
            Server.proofEmail.prove.test(null, FinishProof(key, "ABCDEF"))
        }
    }

    @Test
    fun aPinCannotBeReplayed() = loginTest {
        val emails = Server.email() as TestEmailService
        val address = "newcomer@example.com"

        val key = Server.proofEmail.start.test(null, address)
        val pin = assertNotNull(PIN_PATTERN.find(assertNotNull(emails.lastEmailTo(address)).plainText)?.value)

        assertNotNull(
            Server.subjects.login.test(
                null,
                listOf(Server.proofEmail.prove.test(null, FinishProof(key, pin)))
            ).refreshToken
        )

        // Same key and code a second time must not mint another session.
        assertFailsWith<Exception> {
            Server.proofEmail.prove.test(null, FinishProof(key, pin))
        }
    }

    @Test
    fun loggingInTwiceReusesTheSameUser() = loginTest {
        val emails = Server.email() as TestEmailService
        val address = "newcomer@example.com"

        repeat(2) {
            val key = Server.proofEmail.start.test(null, address)
            val pin = assertNotNull(PIN_PATTERN.find(assertNotNull(emails.lastEmailTo(address)).plainText)?.value)
            val proof = Server.proofEmail.prove.test(null, FinishProof(key, pin))
            assertNotNull(Server.subjects.login.test(null, listOf(proof)).refreshToken)
        }

        assertEquals(
            1,
            Server.userInfo.table().count(condition { it.email eq address }),
            "the second login should reuse the user created by the first",
        )
    }
}
