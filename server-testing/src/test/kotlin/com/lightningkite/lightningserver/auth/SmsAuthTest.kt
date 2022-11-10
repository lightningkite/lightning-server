package com.lightningkite.lightningdb.test.com.lightningkite.lightningserver.auth

import com.lightningkite.lightningdb.collection
import com.lightningkite.lightningdb.test.TestSettings
import com.lightningkite.lightningdb.test.User
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.ModelInfo
import com.lightningkite.lightningserver.email.ConsoleEmailClient
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.test
import com.lightningkite.lightningserver.sms.ConsoleSMSClient
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.fail

class SmsAuthTest {
    init {
        TestSettings
    }
    @Test
    fun testPinCorrect() {
        val info = ModelInfo<User, User, UUID>(
            getCollection = { TestSettings.database().collection() },
            forUser = { this }
        )
        val phoneAccess: UserPhoneAccess<User, UUID> = info.userPhoneAccess { User(email = "$it@phone", phoneNumber = it) }
        val path = ServerPath("auth")
        val baseAuth = BaseAuthEndpoints(path, phoneAccess, TestSettings.jwtSigner)
        val phoneAuth = SmsAuthEndpoints(baseAuth, phoneAccess, TestSettings.cache, TestSettings.sms)
        runBlocking {
            phoneAuth.loginSms.implementation(Unit, "8013693729")
            val pinRegex = Regex("[0-9][0-9][0-9][0-9][0-9][0-9]")
            val pin = (TestSettings.sms() as ConsoleSMSClient).lastMessageSent?.message?.let {
                pinRegex.find(it)?.value
            }!!
            val token = phoneAuth.loginSmsPin.implementation(Unit, PhonePinLogin("8013693729", pin))
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
        val phoneAccess: UserPhoneAccess<User, UUID> = info.userPhoneAccess { User(email = "$it@phone", phoneNumber = it) }
        val path = ServerPath("auth")
        val baseAuth = BaseAuthEndpoints(path, phoneAccess, TestSettings.jwtSigner)
        val phoneAuth = SmsAuthEndpoints(baseAuth, phoneAccess, TestSettings.cache, TestSettings.sms)
        runBlocking {
            phoneAuth.loginSms.implementation(Unit, "8013693729")
            val pinRegex = Regex("[0-9][0-9][0-9][0-9][0-9][0-9]")
            val pin = "wrong"
            try {
                phoneAuth.loginSmsPin.implementation(Unit, PhonePinLogin("8013693729", pin))
                fail()
            } catch (e: BadRequestException) {
                assertEquals("Incorrect PIN", e.body?.data)
            }
        }
    }
}