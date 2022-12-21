package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.collection
import com.lightningkite.lightningdb.test.TestSettings
import com.lightningkite.lightningdb.test.User
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.ModelInfo
import com.lightningkite.lightningserver.auth.userEmailAccess
import com.lightningkite.lightningserver.email.ConsoleEmailClient
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.test
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.fail

class EmailAuthTest {
    init {
        TestSettings
    }
    @Test
    fun testPinCorrect() {
        val info = ModelInfo<User, User, UUID>(
            getCollection = { TestSettings.database().collection() },
            forUser = { this }
        )
        val emailAccess: UserEmailAccess<User, UUID> = info.userEmailAccess { User(email = it, phoneNumber = it) }
        val path = ServerPath("auth")
        val baseAuth = BaseAuthEndpoints(path, emailAccess, TestSettings.jwtSigner)
        val emailAuth = EmailAuthEndpoints(baseAuth, emailAccess, TestSettings.cache, TestSettings.email)
        runBlocking {
            emailAuth.loginEmail.implementation(Unit, "joseph@lightningkite.com")
            val pinRegex = Regex("[0-9][0-9][0-9][0-9][0-9][0-9]")
            val pin = (TestSettings.email() as ConsoleEmailClient).lastEmailSent?.message?.let {
                pinRegex.find(it)?.value
            }!!
            val token = emailAuth.loginEmailPin.implementation(Unit, EmailPinLogin("joseph@lightningkite.com", pin))
            assertEquals(
                HttpStatus.OK, baseAuth.getSelf.route.test(
                headers = HttpHeaders(HttpHeader.Authorization to token, HttpHeader.ContentType to "application/json")
            ).status)
        }
    }
    @Test
    fun testPinIncorrect() {
        val info = ModelInfo<User, User, UUID>(
            getCollection = { TestSettings.database().collection() },
            forUser = { this }
        )
        val emailAccess: UserEmailAccess<User, UUID> = info.userEmailAccess { User(email = it, phoneNumber = it) }
        val path = ServerPath("auth")
        val baseAuth = BaseAuthEndpoints(path, emailAccess, TestSettings.jwtSigner)
        val emailAuth = EmailAuthEndpoints(baseAuth, emailAccess, TestSettings.cache, TestSettings.email)
        runBlocking {
            emailAuth.loginEmail.implementation(Unit, "joseph@lightningkite.com")
            val pinRegex = Regex("[0-9][0-9][0-9][0-9][0-9][0-9]")
            val pin = "wrong"
            try {
                emailAuth.loginEmailPin.implementation(Unit, EmailPinLogin("joseph@lightningkite.com", pin))
                fail()
            } catch (e: BadRequestException) {
                assertEquals("Incorrect PIN", e.message)
            }
        }
    }
}