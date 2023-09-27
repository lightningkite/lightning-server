@file:UseContextualSerialization(UUID::class)

package com.lightningkite.lightningserver.auth.subject.test

import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.auth.proof.*
import com.lightningkite.lightningserver.email.TestEmailClient
import com.lightningkite.lightningserver.typed.AuthAndPathParts
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.UseContextualSerialization
import org.junit.Test
import java.util.*

class AuthEndpointsForSubjectTest {

    @Test
    fun test(): Unit = runBlocking {
        val info = TestSettings.proofEmail.start.implementation(AuthAndPathParts(null, null, arrayOf()), "test@test.com")
        val pinRegex = Regex("[0-9][0-9][0-9][0-9][0-9][0-9]")
        val pin = (TestSettings.email() as TestEmailClient).lastEmailSent?.plainText?.let {
            pinRegex.find(it)?.value
        }!!
        val proof1 = TestSettings.proofEmail.prove.implementation(AuthAndPathParts(null, null, arrayOf()), ProofEvidence(
            value = info,
            secret = pin
        ))
        val result = TestSettings.testUserSubject.login.implementation(AuthAndPathParts(null, null, arrayOf()), listOf(proof1))
        println(result)
        assert(result.session != null)
    }

}