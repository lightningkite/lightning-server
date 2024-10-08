package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.auth.old.EmailPinLogin
import com.lightningkite.lightningserver.email.TestEmailClient
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.test
import com.lightningkite.lightningserver.typed.AuthAndPathParts
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class EmailAuthTest {

    @Test
    fun testPinCorrect() {
        runBlocking {
            TestSettings.emailAuth.loginEmail.implementation(AuthAndPathParts(null, null, arrayOf()), "joseph@lightningkite.com")
            val pinRegex = Regex("[0-9][0-9][0-9][0-9][0-9][0-9]")
            val pin = (TestSettings.email() as TestEmailClient).lastEmailSent?.plainText?.let {
                pinRegex.find(it)?.value
            }!!
            val token = TestSettings.emailAuth.loginEmailPin.implementation(AuthAndPathParts(null, null, arrayOf()), EmailPinLogin("joseph@lightningkite.com", pin))
            assertEquals(
                HttpStatus.OK, TestSettings.baseAuth.getSelf.route.endpoint.test(
                headers = HttpHeaders(HttpHeader.Authorization to token, HttpHeader.ContentType to "application/json")
            ).status)
        }
    }
    @Test
    fun testPinIncorrect() {
        runBlocking {
            TestSettings.emailAuth.loginEmail.implementation(AuthAndPathParts(null, null, arrayOf()), "joseph@lightningkite.com")
            val pinRegex = Regex("[0-9][0-9][0-9][0-9][0-9][0-9]")
            val pin = "wrong"
            try {
                TestSettings.emailAuth.loginEmailPin.implementation(AuthAndPathParts(null, null, arrayOf()), EmailPinLogin("joseph@lightningkite.com", pin))
                fail()
            } catch (e: BadRequestException) {
                assertEquals("Incorrect PIN.  4 attempts remain.", e.message)
            }
        }
    }
}